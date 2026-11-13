"""공공데이터 → 앱 assets/zones.json (보행자 위험 지점)

사용: python3 -I tools/make_zones.py <공공데이터 폴더> app/src/main/assets/zones.json

넣는 것(이름은 음성 안내에 그대로 쓰임):
1. 보행자 사고가 잦은 곳 — 도로교통공단 교통사고분석 개방포털 '보행자 교통사고 다발지역' CSV(19_25_pedstrians.csv, CP949).
   선정 기준(포털 원문): 최근 3년간 보행자 사망·중상 사고, 반경 100m 안 7건 이상.
2. 어르신 보행 사고가 잦은 곳 — 전국교통사고다발지역표준데이터(사고다발지역_표준데이터.json) 중 '보행노인', 2022년 이후.
3. 어린이 보행 사고가 잦은 곳 — 같은 데이터의 '보행어린이'·'스쿨존어린이', 2022년 이후.
4. 집중조명이 없는 횡단보도 — 전국횡단보도표준데이터(횡단보도_표준데이터.json) 중 집중조명시설유무(cnctrLghtFcltyYn)='N'.
   빈칸(미기재)은 넣지 않는다. 제공 지자체가 일부라 지역마다 빠진 곳이 있다.
같은 이름에서 위경도 소수 4자리가 같은 지점은 하나만 남긴다(사고다발은 가장 최근 해).
"""
import csv
import io
import json
import os
import sys

RECENT_FROM = "2022"


def pedestrians_csv(path):
    best = {}
    for r in csv.DictReader(io.open(path, encoding="cp949")):
        key = (round(float(r["위도"]), 4), round(float(r["경도"]), 4))
        if key not in best or r["사고다발지id"] > best[key]["사고다발지id"]:
            best[key] = r
    for r in best.values():
        yield ("보행자 사고가 잦은 곳", float(r["위도"]), float(r["경도"]),
               f"도로교통공단 보행자 사고다발지역 {r['사고다발지id'][:4]} · {r['지점명'].strip()}")


def accident_areas(path):
    names = {"보행노인": "어르신 보행 사고가 잦은 곳", "보행어린이": "어린이 보행 사고가 잦은 곳", "스쿨존어린이": "어린이 보행 사고가 잦은 곳"}
    for x in json.load(open(path, encoding="utf-8"))["items"]:
        name = names.get(x["acdntTypeSe"])
        if name and x["acdntYear"] >= RECENT_FROM and x["latitude"] and x["longitude"]:
            yield (name, float(x["latitude"]), float(x["longitude"]),
                   f"사고다발지역표준데이터 {x['acdntYear']} {x['acdntTypeSe']} · {x['acdntAreaLcNm'].strip()}")


def dark_crosswalks(path):
    for x in json.load(open(path, encoding="utf-8"))["items"]:
        if x.get("cnctrLghtFcltyYn") == "N" and x["latitude"] and x["longitude"]:
            addr = (x.get("rdnmadr") or x.get("lnmadr") or "").strip()
            yield ("집중조명이 없는 횡단보도", float(x["latitude"]), float(x["longitude"]),
                   f"횡단보도표준데이터 {x.get('referenceDate', '')} · {addr}")


def main(src_dir, dst):
    seen = set()
    out = []
    counts = {}
    sources = [
        pedestrians_csv(os.path.join(src_dir, "19_25_pedstrians.csv")),
        accident_areas(os.path.join(src_dir, "사고다발지역_표준데이터.json")),
        dark_crosswalks(os.path.join(src_dir, "횡단보도_표준데이터.json")),
    ]
    for gen in sources:
        for name, lat, lon, source in gen:
            key = (name, round(lat, 4), round(lon, 4))
            if key in seen:
                continue
            seen.add(key)
            out.append({"name": name, "lat": round(lat, 6), "lon": round(lon, 6), "source": source})
            counts[name] = counts.get(name, 0) + 1
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    print(f"zones {len(out)} -> {dst}")
    for k, v in counts.items():
        print(f"  {k}: {v}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
