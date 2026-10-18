import { reportMapError, type MapErrorCode } from "./map-diagnostics";
import type { LocationWrite, Marker } from "../shared/types";
export interface Viewport {
  south: string;
  north: string;
  west: string;
  east: string;
  level: number;
  latitude: number;
  longitude: number;
}
export interface MapHandle {
  center: () => { latitude: number; longitude: number };
  move: (lat: number, lon: number) => void;
  zoom: (delta: number) => void;
  markers: (items: Marker[], select: (m: Marker) => void) => void;
  preview: (latitude: number, longitude: number) => void;
  dispose: () => void;
}
export interface MapAdapter {
  search: (query: string, page?: number) => Promise<LocationWrite[]>;
  reverse: (location: LocationWrite) => Promise<LocationWrite>;
  mount: (
    element: HTMLElement,
    viewport: Viewport,
    onIdle: (v: Viewport) => void,
  ) => MapHandle;
}
interface Point {
  getLat(): number;
  getLng(): number;
}
interface Bounds {
  getSouthWest(): Point;
  getNorthEast(): Point;
}
interface KakaoMap {
  getCenter(): Point;
  setCenter(p: Point): void;
  getLevel(): number;
  setLevel(level: number): void;
  getBounds(): Bounds;
}
interface KakaoMarker {
  setMap(map: KakaoMap | null): void;
}
interface SearchItem {
  id: string;
  place_name: string;
  y: string;
  x: string;
  road_address_name: string;
  address_name: string;
}
interface Address {
  road_address?: { address_name: string };
  address?: { address_name: string };
}
interface Region {
  region_type: string;
  code: string;
  region_1depth_name: string;
  region_2depth_name: string;
}
interface SDK {
  load: (fn: () => void) => void;
  LatLng: new (lat: number, lon: number) => Point;
  Map: new (
    el: HTMLElement,
    options: { center: Point; level: number },
  ) => KakaoMap;
  Marker: new (options: { position: Point; title: string }) => KakaoMarker;
  MarkerClusterer: new (options: {
    map: KakaoMap;
    averageCenter: boolean;
    minLevel: number;
  }) => { clear: () => void; addMarkers: (markers: KakaoMarker[]) => void };
  event: {
    addListener: (target: object, event: string, handler: () => void) => void;
    removeListener: (
      target: object,
      event: string,
      handler: () => void,
    ) => void;
  };
  services: {
    Places: new () => {
      keywordSearch: (
        q: string,
        callback: (items: SearchItem[], status: string) => void,
        options: { page: number },
      ) => void;
    };
    Geocoder: new () => {
      coord2Address: (
        x: number,
        y: number,
        callback: (items: Address[], status: string) => void,
      ) => void;
      coord2RegionCode: (
        x: number,
        y: number,
        callback: (items: Region[], status: string) => void,
      ) => void;
    };
    Status: { OK: string; ZERO_RESULT: string };
  };
}
declare global {
  interface Window {
    kakao?: { maps: SDK };
  }
}
let loading: Promise<SDK> | null = null;
export function loadSDK(): Promise<SDK> {
  const key = process.env.NEXT_PUBLIC_KAKAO_MAP_APP_KEY;
  if (!key)
    return Promise.reject(
      new Error("지도 키가 설정되지 않았습니다. 위치 없이 기록할 수 있습니다."),
    );
  if (!loading)
    loading = new Promise((resolve, reject) => {
      const script = document.createElement("script");
      script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(key)}&autoload=false&libraries=services,clusterer`;
      script.async = true;
      const timer = setTimeout(() => {
        script.remove();
        script.onload = null;
        loading = null;
        reportMapError("SDK_TIMEOUT");
        reject(new Error("지도 연결 시간이 초과되었습니다."));
      }, 15000);
      script.onerror = () => {
        reportMapError("SDK_LOAD");
        clearTimeout(timer);
        script.remove();
        loading = null;
        reject(
          new Error(
            "지도를 불러오지 못했습니다. 연결과 허용 도메인을 확인하세요.",
          ),
        );
      };
      script.onload = () => {
        const sdk = window.kakao?.maps;
        if (!sdk) {
          reportMapError("SDK_UNAVAILABLE");
          script.remove();
          loading = null;
          clearTimeout(timer);
          reject(new Error("지도 서비스를 사용할 수 없습니다."));
          return;
        }
        sdk.load(() => {
          clearTimeout(timer);
          resolve(sdk);
        });
      };
      document.head.appendChild(script);
    });
  return loading;
}
export function coordinate(
  source: LocationWrite["source"],
  lat: number,
  lon: number,
): LocationWrite {
  if (
    !Number.isFinite(lat) ||
    !Number.isFinite(lon) ||
    Math.abs(lat) > 90 ||
    Math.abs(lon) > 180
  )
    throw new Error("좌표 범위를 확인하세요.");
  return {
    source,
    latitude: lat.toFixed(6),
    longitude: lon.toFixed(6),
    externalPlaceId: null,
    placeName: null,
    accuracyM: null,
    capturedAt: null,
    lowAccuracyAccepted: false,
    roadAddress: null,
    parcelAddress: null,
    regionCode: null,
    regionSido: null,
    regionSigungu: null,
  };
}
export function currentLocation(): Promise<LocationWrite> {
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) {
      reject(new Error("이 브라우저는 위치 기능을 지원하지 않습니다."));
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (p) =>
        resolve({
          ...coordinate("CURRENT", p.coords.latitude, p.coords.longitude),
          accuracyM: p.coords.accuracy.toFixed(3),
          capturedAt: new Date(p.timestamp).toISOString(),
        }),
      (e) =>
        reject(
          new Error(
            e.code === 1
              ? "위치 권한이 거부되었습니다. 브라우저 권한 설정을 확인하거나 위치 없이 계속하세요."
              : e.code === 3
                ? "위치 확인 시간이 초과되었습니다. 다시 시도하거나 위치 없이 계속하세요."
                : "현재 위치를 확인할 수 없습니다.",
          ),
        ),
      { enableHighAccuracy: true, timeout: 10000, maximumAge: 0 },
    );
  });
}
export async function kakaoAdapter(): Promise<MapAdapter> {
  const sdk = await loadSDK();
  const geocoder = new sdk.services.Geocoder();
  const reverse = async (l: LocationWrite) => {
    const x = Number(l.longitude),
      y = Number(l.latitude);
    const request = <T>(
      call: (done: (items: T[], status: string) => void) => void,
      failed: MapErrorCode,
      timeout: MapErrorCode,
    ) =>
      new Promise<T[]>((resolve) => {
        let settled = false;
        const timer = setTimeout(() => {
          if (settled) return;
          settled = true;
          reportMapError(timeout);
          resolve([]);
        }, 10000);
        const done = (items: T[], status: string) => {
          if (settled) return;
          settled = true;
          clearTimeout(timer);
          if (
            status !== sdk.services.Status.OK &&
            status !== sdk.services.Status.ZERO_RESULT
          )
            reportMapError(failed);
          resolve(status === sdk.services.Status.OK ? items : []);
        };
        try {
          call(done);
        } catch {
          done([], "ERROR");
        }
      });
    const [addresses, regions] = await Promise.all([
      request<Address>(
        (done) => geocoder.coord2Address(x, y, done),
        "ADDRESS_FAILED",
        "ADDRESS_TIMEOUT",
      ),
      request<Region>(
        (done) => geocoder.coord2RegionCode(x, y, done),
        "REGION_FAILED",
        "REGION_TIMEOUT",
      ),
    ]);
    const b = regions.find((r) => r.region_type === "B");
    return {
      ...l,
      roadAddress: addresses[0]?.road_address?.address_name || l.roadAddress,
      parcelAddress: addresses[0]?.address?.address_name || l.parcelAddress,
      regionCode: b?.code.slice(0, 5) || null,
      regionSido: b?.region_1depth_name || null,
      regionSigungu: b?.region_2depth_name || null,
    };
  };
  return {
    reverse,
    search: (query, page = 1) =>
      new Promise((resolve, reject) => {
        let settled = false;
        const timer = setTimeout(() => {
          if (settled) return;
          settled = true;
          reportMapError("SEARCH_TIMEOUT");
          reject(
            new Error("장소 검색 시간이 초과되었습니다. 다시 시도하세요."),
          );
        }, 10000);
        try {
          new sdk.services.Places().keywordSearch(
            query,
            (items, status) => {
              if (settled) return;
              settled = true;
              clearTimeout(timer);
              if (status === sdk.services.Status.ZERO_RESULT) {
                resolve([]);
                return;
              }
              if (status !== sdk.services.Status.OK) {
                reportMapError("SEARCH_FAILED");
                reject(new Error("장소 검색에 실패했습니다."));
                return;
              }
              resolve(
                items.map((i) => ({
                  ...coordinate("SEARCH", Number(i.y), Number(i.x)),
                  externalPlaceId: i.id || null,
                  placeName: i.place_name,
                  roadAddress: i.road_address_name || null,
                  parcelAddress: i.address_name || null,
                })),
              );
            },
            { page },
          );
        } catch {
          settled = true;
          clearTimeout(timer);
          reportMapError("SEARCH_FAILED");
          reject(new Error("장소 검색에 실패했습니다."));
        }
      }),
    mount: (element, v, onIdle) => {
      const map = new sdk.Map(element, {
        center: new sdk.LatLng(v.latitude, v.longitude),
        level: v.level,
      });
      const cluster = new sdk.MarkerClusterer({
        map,
        averageCenter: true,
        minLevel: 6,
      });
      let markers: KakaoMarker[] = [];
      let bindings: { marker: KakaoMarker; handler: () => void }[] = [];
      const idle = () => {
        const b = map.getBounds(),
          sw = b.getSouthWest(),
          ne = b.getNorthEast(),
          center = map.getCenter();
        onIdle({
          south: sw.getLat().toFixed(6),
          north: ne.getLat().toFixed(6),
          west: sw.getLng().toFixed(6),
          east: ne.getLng().toFixed(6),
          level: map.getLevel(),
          latitude: center.getLat(),
          longitude: center.getLng(),
        });
      };
      sdk.event.addListener(map, "idle", idle);
      queueMicrotask(idle);
      const clear = () => {
        cluster.clear();
        bindings.forEach((b) =>
          sdk.event.removeListener(b.marker, "click", b.handler),
        );
        bindings = [];
        markers.forEach((m) => m.setMap(null));
        markers = [];
      };
      return {
        center: () => ({
          latitude: map.getCenter().getLat(),
          longitude: map.getCenter().getLng(),
        }),
        move: (lat, lon) => map.setCenter(new sdk.LatLng(lat, lon)),
        zoom: (delta) =>
          map.setLevel(Math.min(14, Math.max(1, map.getLevel() + delta))),
        markers: (items, select) => {
          clear();
          markers = items.map((item) => {
            const marker = new sdk.Marker({
              position: new sdk.LatLng(
                Number(item.latitude),
                Number(item.longitude),
              ),
              title: `${item.title} · ${item.amount}원 · ${item.count}건`,
            });
            const handler = () => select(item);
            sdk.event.addListener(marker, "click", handler);
            bindings.push({ marker, handler });
            return marker;
          });
          cluster.addMarkers(markers);
        },
        preview: (latitude, longitude) => {
          clear();
          const marker = new sdk.Marker({
            position: new sdk.LatLng(latitude, longitude),
            title: "선택한 위치",
          });
          marker.setMap(map);
          markers = [marker];
        },
        dispose: () => {
          clear();
          sdk.event.removeListener(map, "idle", idle);
          element.replaceChildren();
        },
      };
    },
  };
}
export const initialViewport: Viewport = {
  south: "37",
  north: "38",
  west: "126",
  east: "128",
  level: 6,
  latitude: 37.5665,
  longitude: 126.978,
};
