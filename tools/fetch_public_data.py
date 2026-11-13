"""공공데이터포털 표준데이터 API 두 개를 전부 내려받아 원본 JSON으로 저장한다.

사용: DATA_GO_KR_SERVICE_KEY=<공공데이터포털 일반 인증키> python3 tools/fetch_public_data.py <저장 폴더>
- 키는 환경변수로만 읽고 파일에 쓰지 않는다. 두 API 모두 공공데이터포털에서 활용신청(자동승인)이 필요하다.
- 전국교통사고다발지역표준데이터: https://www.data.go.kr/data/15029185/standard.do
- 전국횡단보도표준데이터:        https://www.data.go.kr/data/15028201/standard.do
"""
import json
import os
import sys
import time
import urllib.parse
import urllib.request

APIS = {
    "사고다발지역_표준데이터.json": "https://api.data.go.kr/openapi/tn_pubr_public_acdnt_area_api",
    "횡단보도_표준데이터.json": "https://api.data.go.kr/openapi/tn_pubr_public_crosswalk_api",
}
ROWS = 1000


def service_key() -> str:
    k = os.environ.get("DATA_GO_KR_SERVICE_KEY")
    if not k:
        sys.exit("환경변수 DATA_GO_KR_SERVICE_KEY가 없습니다.")
    return k


def fetch_page(url: str, key: str, page: int) -> dict:
    q = urllib.parse.urlencode({"serviceKey": key, "pageNo": page, "numOfRows": ROWS, "type": "json"})
    for attempt in range(3):
        try:
            raw = urllib.request.urlopen(f"{url}?{q}", timeout=90).read().decode("utf-8", "replace")
            return json.loads(raw)
        except Exception as e:  # 네트워크 일시 오류만 재시도
            if attempt == 2:
                raise RuntimeError(f"page {page} 실패: {type(e).__name__}") from None
            time.sleep(2)
    raise AssertionError


def fetch_all(url: str, key: str) -> list:
    first = fetch_page(url, key, 1)
    total = int(first["body"]["totalCount"])
    items = list(first["body"]["items"]["item"])
    pages = (total + ROWS - 1) // ROWS
    for p in range(2, pages + 1):
        items += fetch_page(url, key, p)["body"]["items"]["item"]
        time.sleep(0.2)
    if len(items) != total:
        raise RuntimeError(f"건수 불일치: total {total}, 받은 것 {len(items)}")
    return items


def main(out_dir: str) -> None:
    key = service_key()
    os.makedirs(out_dir, exist_ok=True)
    for name, url in APIS.items():
        items = fetch_all(url, key)
        with open(os.path.join(out_dir, name), "w", encoding="utf-8") as f:
            json.dump({"source": url, "fetched": time.strftime("%Y-%m-%d"), "count": len(items), "items": items},
                      f, ensure_ascii=False)
        print(f"{name}: {len(items)}건")


if __name__ == "__main__":
    main(sys.argv[1])
