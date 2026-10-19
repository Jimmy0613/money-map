"use client";
import { useEffect, useRef, useState } from "react";
import type { LocationWrite } from "../shared/types";
import {
  coordinate,
  currentLocation,
  initialViewport,
  kakaoAdapter,
  type MapHandle,
} from "./map-adapter";
export function LocationPreview({ location }: { location: LocationWrite }) {
  const element = useRef<HTMLDivElement>(null);
  const [failed, setFailed] = useState(false);
  const { latitude, longitude } = location;
  useEffect(() => {
    let live = true;
    let map: MapHandle | undefined;
    kakaoAdapter()
      .then((adapter) => {
        if (!live || !element.current) return;
        map = adapter.mount(
          element.current,
          {
            ...initialViewport,
            latitude: Number(latitude),
            longitude: Number(longitude),
            level: 3,
          },
          () => {},
        );
        map.preview(Number(latitude), Number(longitude));
      })
      .catch(() => {
        if (live) setFailed(true);
      });
    return () => {
      live = false;
      map?.dispose();
    };
  }, [latitude, longitude]);
  return (
    <>
      <div
        ref={element}
        className="map-canvas"
        aria-label="선택한 위치 미리보기 지도"
      />
      {failed && (
        <p role="status">
          미리보기 지도를 표시하지 못했습니다. 아래 주소와 좌표를 확인해 주세요.
        </p>
      )}
    </>
  );
}
export function LocationPicker({
  value,
  onChange,
}: {
  value: LocationWrite | null;
  onChange: (v: LocationWrite | null) => void;
}) {
  const [showSavedPreview, setShowSavedPreview] = useState(false);
  const sourceName = (source: LocationWrite["source"]) =>
    ({ CURRENT: "현재 위치", SEARCH: "장소 검색", MAP: "지도 직접 선택" })[
      source
    ];
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<LocationWrite[] | null>(null);
  const [draft, setDraft] = useState<LocationWrite | null>(null);
  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState<"search" | "map" | "current">("current");
  const dialog = useRef<HTMLDialogElement>(null);
  const mapEl = useRef<HTMLDivElement>(null);
  const handle = useRef<MapHandle | null>(null);
  const searchSequence = useRef(0);
  const selectionSequence = useRef(0);
  useEffect(() => {
    if (open) dialog.current?.showModal();
    else dialog.current?.close();
  }, [open]);
  useEffect(() => {
    if (!open || mode !== "search") return;
    const seq = ++searchSequence.current;
    const timer = setTimeout(() => {
      if (query.trim().length < 2) {
        setResults(null);
        setBusy(false);
        return;
      }
      setBusy(true);
      kakaoAdapter()
        .then((a) => a.search(query.trim()))
        .then((r) => {
          if (seq === searchSequence.current) {
            setResults(r);
            setError("");
          }
        })
        .catch((e) => {
          if (seq === searchSequence.current) setError(e.message);
        })
        .finally(() => {
          if (seq === searchSequence.current) setBusy(false);
        });
    }, 300);
    return () => {
      clearTimeout(timer);
      searchSequence.current = seq + 1;
    };
  }, [query, open, mode]);
  useEffect(() => {
    if (!open || mode !== "map" || !mapEl.current) return;
    let live = true;
    kakaoAdapter()
      .then((a) => {
        if (live && mapEl.current)
          handle.current = a.mount(mapEl.current, initialViewport, () => {});
      })
      .catch((e) => {
        if (live) setError(e.message);
      });
    return () => {
      live = false;
      handle.current?.dispose();
      handle.current = null;
    };
  }, [open, mode]);
  async function preview(l: LocationWrite) {
    const seq = ++selectionSequence.current;
    setDraft(l);
    try {
      const a = await kakaoAdapter();
      const resolved = await a.reverse(l);
      if (seq === selectionSequence.current) {
        setDraft((current) =>
          current
            ? { ...resolved, lowAccuracyAccepted: current.lowAccuracyAccepted }
            : null,
        );
        setError(
          resolved.roadAddress || resolved.parcelAddress
            ? ""
            : "이 좌표의 주소를 확인하지 못했습니다. 좌표만 저장하거나 장소 검색으로 위치를 선택할 수 있습니다.",
        );
      }
    } catch {
      if (seq === selectionSequence.current)
        setError(
          "주소를 확인하지 못했습니다. 좌표만 저장하거나 위치 없이 계속할 수 있습니다.",
        );
    }
  }
  async function current() {
    const seq = ++selectionSequence.current;
    setBusy(true);
    setError("");
    try {
      const location = await currentLocation();
      if (seq !== selectionSequence.current) return;
      // preview owns the next sequence while it resolves the address.
      setBusy(false);
      await preview(location);
    } catch (e) {
      if (seq === selectionSequence.current) setError((e as Error).message);
    } finally {
      if (seq === selectionSequence.current) setBusy(false);
    }
  }
  function close() {
    selectionSequence.current++;
    setOpen(false);
    setDraft(null);
    setBusy(false);
  }
  function choose(m: typeof mode) {
    selectionSequence.current++;
    setMode(m);
    setError("");
    setOpen(true);
    setDraft(null);
    setBusy(false);
    setResults(null);
  }
  return (
    <fieldset>
      <legend>위치 · 선택 사항</legend>
      <p className="muted">
        선택한 장소만 저장합니다. 지도·주소 조회 시 위치 정보가 Kakao에
        전달됩니다.
      </p>
      {value ? (
        <div>
          <p>위치 출처: {sourceName(value.source)}</p>
          <button
            type="button"
            className="quiet"
            aria-expanded={showSavedPreview}
            onClick={() => setShowSavedPreview(!showSavedPreview)}
          >
            {showSavedPreview
              ? "저장할 위치 지도 닫기"
              : "저장할 위치 지도 확인"}
          </button>
          {showSavedPreview && (
            <LocationPreview
              key={`${value.latitude}:${value.longitude}`}
              location={value}
            />
          )}
          <p>
            {value.placeName ||
              value.roadAddress ||
              value.parcelAddress ||
              "선택한 위치"}
            <br />
            {value.latitude}, {value.longitude}
          </p>
          <button
            type="button"
            className="quiet"
            onClick={() => {
              setShowSavedPreview(false);
              onChange(null);
            }}
          >
            저장할 위치 제거
          </button>
        </div>
      ) : (
        <p>위치 없이 기록합니다.</p>
      )}
      <div className="actions">
        <button
          type="button"
          className="quiet"
          onClick={() => choose("current")}
        >
          현재 위치
        </button>
        <button
          type="button"
          className="quiet"
          onClick={() => choose("search")}
        >
          장소 검색
        </button>
        <button type="button" className="quiet" onClick={() => choose("map")}>
          지도 선택
        </button>
      </div>
      <dialog ref={dialog} onCancel={close} aria-labelledby="location-title">
        <div className="row">
          <h2 id="location-title">위치 선택</h2>
          <button type="button" className="quiet" onClick={close}>
            닫기
          </button>
        </div>
        {mode === "current" && (
          <button type="button" onClick={current} disabled={busy}>
            현재 위치 확인 · 재시도
          </button>
        )}
        {mode === "search" && (
          <>
            <label>
              장소 검색어
              <input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                onKeyDown={(event) => {
                  // Search is debounced; Enter must not submit the transaction form.
                  if (event.key === "Enter") event.preventDefault();
                }}
                placeholder="두 글자 이상 입력"
              />
            </label>
            {results?.length === 0 && <p>검색 결과가 없습니다.</p>}
            <ul className="list">
              {results?.map((l, i) => (
                <li key={l.externalPlaceId || i}>
                  <button
                    type="button"
                    className="quiet"
                    onClick={() => preview(l)}
                  >
                    {l.placeName}
                    <br />
                    {l.roadAddress || l.parcelAddress}
                  </button>
                </li>
              ))}
            </ul>
          </>
        )}
        {mode === "map" && (
          <>
            <div
              className="map-canvas"
              ref={mapEl}
              aria-label="위치 선택 지도"
            />
            <div className="actions">
              {[
                ["북", 0.001, 0],
                ["남", -0.001, 0],
                ["동", 0, 0.001],
                ["서", 0, -0.001],
              ].map(([label, lat, lon]) => (
                <button
                  type="button"
                  key={label}
                  className="quiet"
                  onClick={() => {
                    const h = handle.current;
                    if (h) {
                      const p = h.center();
                      h.move(
                        p.latitude + Number(lat),
                        p.longitude + Number(lon),
                      );
                    }
                  }}
                >
                  {label}쪽 이동
                </button>
              ))}
              <button
                type="button"
                onClick={() => {
                  const p = handle.current?.center();
                  if (p) preview(coordinate("MAP", p.latitude, p.longitude));
                }}
              >
                지도 중심 선택
              </button>
            </div>
          </>
        )}
        {busy && <p role="status">위치 확인 중…</p>}
        <p role="alert">{error}</p>
        {draft && (
          <section className="card">
            <h3>저장할 위치 미리보기</h3>
            <p>위치 출처: {sourceName(draft.source)}</p>
            {mode !== "map" && (
              <LocationPreview
                key={`${draft.latitude}:${draft.longitude}`}
                location={draft}
              />
            )}
            <p>
              {draft.placeName ||
                draft.roadAddress ||
                draft.parcelAddress ||
                "주소 없음"}
              <br />
              {draft.latitude}, {draft.longitude}
            </p>
            {draft.accuracyM && <p>오차 반경 {draft.accuracyM}m</p>}
            {Number(draft.accuracyM) > 1000 && !draft.lowAccuracyAccepted ? (
              <div className="notice">
                <p>오차 반경이 1,000m를 초과했습니다.</p>
                <button
                  type="button"
                  onClick={() =>
                    setDraft({ ...draft, lowAccuracyAccepted: true })
                  }
                >
                  오차를 확인했으며 그대로 사용
                </button>
              </div>
            ) : (
              <button
                type="button"
                onClick={() => {
                  onChange(draft);
                  close();
                }}
              >
                이 위치 확정
              </button>
            )}
          </section>
        )}
        <button
          type="button"
          className="quiet"
          onClick={() => {
            onChange(null);
            close();
          }}
        >
          위치 없이 계속하기
        </button>
      </dialog>
    </fieldset>
  );
}
