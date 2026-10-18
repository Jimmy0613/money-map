"use client";
import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api, ApiError, subscribeAuth } from "../../shared/api";
import { useAuth } from "../../shared/providers";
import {
  Filters,
  filterQuery,
  validPeriod,
  monthFilter,
  Status,
  TransactionList,
  type Filter,
} from "../../shared/ui";
import {
  money,
  type Marker,
  type Markers,
  type MarkerDetails,
} from "../../shared/types";
import {
  initialViewport,
  kakaoAdapter,
  type MapHandle,
  type Viewport,
} from "../../features/map-adapter";
let saved: { filter: Filter; viewport: Viewport } | null = null;
subscribeAuth(() => {
  saved = null;
});
export default function Page() {
  const { user } = useAuth();
  const [filter, setFilter] = useState(
    () => saved?.filter || monthFilter(user!.today),
  );
  const [viewport, setViewport] = useState(
    () => saved?.viewport || initialViewport,
  );
  const [sdkError, setSdkError] = useState("");
  const [attempt, setAttempt] = useState(0);
  const [lastResult, setLastResult] = useState<{
    query: string;
    data: Markers;
  } | null>(null);
  const [selected, setSelected] = useState<Marker | null>(null);
  const el = useRef<HTMLDivElement>(null);
  const handle = useRef<MapHandle | null>(null);
  const [readyHandle, setReadyHandle] = useState<MapHandle | null>(null);
  const recoveredRevision = useRef("");
  const sheet = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    let live = true;
    let timer: ReturnType<typeof setTimeout>;
    kakaoAdapter()
      .then((a) => {
        if (live && el.current) {
          handle.current = a.mount(
            el.current,
            saved?.viewport || initialViewport,
            (v) => {
              clearTimeout(timer);
              timer = setTimeout(() => {
                if (live) setViewport(v);
              }, 300);
            },
          );
          setReadyHandle(handle.current);
        }
      })
      .catch((e) => {
        if (live) setSdkError(e.message);
      });
    return () => {
      live = false;
      clearTimeout(timer);
      handle.current?.dispose();
      handle.current = null;
    };
  }, [attempt]);
  useEffect(() => {
    saved = { filter, viewport };
  }, [filter, viewport]);
  const q = filterQuery(filter, true);
  for (const k of ["south", "north", "west", "east", "level"] as const)
    q.set(k, String(viewport[k]));
  const query = q.toString();
  const result = useQuery({
    queryKey: ["map", query],
    queryFn: ({ signal }) => api<Markers>(`/map/markers?${query}`, { signal }),
    enabled: validPeriod(filter.from, filter.to),
    placeholderData: (previous) => previous,
  });
  if (
    result.data &&
    !result.isPlaceholderData &&
    (lastResult?.data !== result.data || lastResult.query !== query)
  )
    setLastResult({ query, data: result.data });
  const displayed = result.data ?? lastResult?.data;
  const currentResult =
    validPeriod(filter.from, filter.to) &&
    !!result.data &&
    !result.isPlaceholderData &&
    !result.isError;
  useEffect(() => {
    if (displayed)
      readyHandle?.markers(displayed.items, (m) => {
        if (currentResult) setSelected(m);
      });
  }, [displayed, currentResult, readyHandle]);
  useEffect(() => {
    if (selected) sheet.current?.showModal();
    else sheet.current?.close();
  }, [selected]);
  const detail = useQuery({
    queryKey: [
      "marker-detail",
      query,
      selected?.markerKey,
      result.data?.revision,
    ],
    queryFn: ({ signal }) =>
      api<MarkerDetails>(
        `/map/marker-details?${query}&markerKey=${encodeURIComponent(selected!.markerKey)}&revision=${result.data!.revision}`,
        { signal },
      ),
    enabled:
      currentResult &&
      !!selected &&
      !!result.data?.items.some((m) => m.markerKey === selected.markerKey),
  });
  if (
    selected &&
    (!currentResult ||
      !result.data?.items.some((m) => m.markerKey === selected.markerKey))
  )
    setSelected(null);
  const refreshMarkers = result.refetch;
  useEffect(() => {
    if (
      !(detail.error instanceof ApiError) ||
      detail.error.code !== "CURSOR_STALE"
    )
      return;
    const key = `${query}:${selected?.markerKey}:${result.data?.revision}`;
    if (recoveredRevision.current === key) return;
    recoveredRevision.current = key;
    let live = true;
    // The new revision changes the detail query key; never refetch the old key.
    void refreshMarkers().then((fresh) => {
      if (
        live &&
        fresh.isSuccess &&
        fresh.data &&
        !fresh.data.items.some((m) => m.markerKey === selected?.markerKey)
      )
        setSelected(null);
    });
    return () => {
      live = false;
    };
  }, [
    detail.error,
    query,
    selected?.markerKey,
    result.data?.revision,
    refreshMarkers,
  ]);
  return (
    <main id="content">
      <h1>소비 지도</h1>
      <p className="muted">
        지출을 장소별로 살펴보세요. 위치 없는 기록도 기간 합계에 포함합니다.
      </p>
      <Filters expenses value={filter} onChange={setFilter} />
      <button
        className="quiet"
        onClick={() => setFilter(monthFilter(user!.today))}
      >
        필터 초기화
      </button>
      {sdkError && (
        <div className="notice">
          <p>{sdkError}</p>
          <button
            className="quiet"
            onClick={() => {
              setSdkError("");
              setAttempt((a) => a + 1);
            }}
          >
            지도 다시 시도
          </button>
        </div>
      )}
      <div className="map-canvas" ref={el} aria-label="소비 지도" />
      <div className="actions">
        {[
          ["북", 0.01, 0],
          ["남", -0.01, 0],
          ["동", 0, 0.01],
          ["서", 0, -0.01],
        ].map(([label, lat, lon]) => (
          <button
            className="quiet"
            key={label}
            onClick={() => {
              const h = handle.current;
              if (h) {
                const c = h.center();
                h.move(c.latitude + Number(lat), c.longitude + Number(lon));
              }
            }}
          >
            {label}쪽
          </button>
        ))}
        <button className="quiet" onClick={() => handle.current?.zoom(-1)}>
          확대
        </button>
        <button className="quiet" onClick={() => handle.current?.zoom(1)}>
          축소
        </button>
      </div>
      <Status
        loading={result.isLoading}
        error={result.error}
        retry={() => {
          if (validPeriod(filter.from, filter.to)) void result.refetch();
        }}
      />
      {displayed && (
        <>
          {!currentResult && (
            <p role="status">
              이전 조회 결과입니다. 현재 조건의 조회가 완료되면 갱신됩니다.
            </p>
          )}
          <div className="card">
            <p>
              현재 영역 <strong>{money(displayed.viewportExpenseTotal)}</strong>{" "}
              · {displayed.viewportCount}건
            </p>
            <p>
              기간 전체 {money(displayed.periodExpenseTotal)} · 위치 없는 지출{" "}
              {displayed.locationlessCount}건
            </p>
            {displayed.requiresNarrowing && (
              <p role="status">
                결과가 많습니다. 지도를 확대하거나 기간·카테고리를 좁혀주세요.
              </p>
            )}
          </div>
          <h2>지도 결과 목록</h2>
          {!displayed.items.length && !displayed.requiresNarrowing && (
            <p>현재 영역에 위치 지출이 없습니다.</p>
          )}
          <ul className="list">
            {displayed.items.map((m) => (
              <li key={m.markerKey}>
                <button
                  className="quiet"
                  disabled={!currentResult}
                  onClick={() => setSelected(m)}
                >
                  {m.title} · {money(m.amount)} · {m.count}건
                </button>
              </li>
            ))}
          </ul>
        </>
      )}
      <dialog
        ref={sheet}
        onCancel={() => setSelected(null)}
        aria-labelledby="marker-title"
      >
        <div className="row">
          <h2 id="marker-title">{selected?.title}</h2>
          <button className="quiet" onClick={() => setSelected(null)}>
            닫기
          </button>
        </div>
        <Status
          loading={detail.isLoading}
          error={detail.error}
          retry={() => {
            if (
              detail.error instanceof ApiError &&
              detail.error.code === "CURSOR_STALE"
            )
              void refreshMarkers();
            else void detail.refetch();
          }}
        />
        {detail.data && (
          <>
            <p>{detail.data.address}</p>
            <p className="amount">{money(detail.data.amount)}</p>
            <p>{detail.data.count}건</p>
            <TransactionList items={detail.data.items} />
            {detail.data.page.hasNext && (
              <p>최근 {detail.data.page.size}건을 표시합니다.</p>
            )}
          </>
        )}
      </dialog>
    </main>
  );
}
