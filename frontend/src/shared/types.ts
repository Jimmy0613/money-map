export type Kind = "INCOME" | "EXPENSE";
export interface User {
  demo?: boolean;
  id: string;
  email: string;
  displayName: string | null;
  timezone: string;
  today: string;
  version: number;
}
export interface Category {
  id: string;
  type: Kind;
  name: string;
  isDefault: boolean;
  archived: boolean;
  icon: string | null;
  color: string | null;
  sortOrder: number;
  version: number;
}
export interface LocationWrite {
  source: "CURRENT" | "SEARCH" | "MAP";
  externalPlaceId: string | null;
  placeName: string | null;
  latitude: string;
  longitude: string;
  accuracyM: string | null;
  capturedAt: string | null;
  lowAccuracyAccepted: boolean;
  roadAddress: string | null;
  parcelAddress: string | null;
  regionCode: string | null;
  regionSido: string | null;
  regionSigungu: string | null;
}
export interface LocationView extends LocationWrite {
  placeId: string | null;
  regionKey: string;
}
export interface TransactionWrite {
  type: Kind;
  amount: string;
  currency: "KRW";
  categoryId: string;
  transactionDate: string;
  memo: string | null;
  location: LocationWrite | null;
}
export interface Transaction {
  id: string;
  type: Kind;
  amount: string;
  currency: "KRW";
  category: { id: string; name: string; archived: boolean };
  transactionDate: string;
  memo: string | null;
  location: LocationView | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}
export interface Page {
  nextCursor: string | null;
  hasNext: boolean;
  size: number;
}
export interface Summary {
  incomeTotal: string;
  expenseTotal: string;
  balance: string;
  incomeCount: number;
  expenseCount: number;
}
export interface TransactionPage {
  items: Transaction[];
  page: Page;
  revision: string;
  totals: Summary & { count: number };
}
export interface Marker {
  markerKey: string;
  title: string;
  latitude: string;
  longitude: string;
  amount: string;
  count: number;
}
export interface Markers {
  items: Marker[];
  viewportExpenseTotal: string;
  viewportCount: number;
  periodExpenseTotal: string;
  locationlessCount: number;
  requiresNarrowing: boolean;
  limit: number;
  revision: string;
}
export interface MarkerDetails {
  markerKey: string;
  title: string;
  address: string | null;
  amount: string;
  count: number;
  items: Transaction[];
  page: Page;
  revision: string;
}
export function money(value: string) {
  return `${BigInt(value).toLocaleString("ko-KR")}원`;
}
export function toWrite(t: Transaction): TransactionWrite {
  const l = t.location;
  return {
    type: t.type,
    amount: t.amount,
    currency: "KRW",
    categoryId: t.category.id,
    transactionDate: t.transactionDate,
    memo: t.memo,
    location: l
      ? {
          source: l.source,
          externalPlaceId: l.externalPlaceId,
          placeName: l.placeName,
          latitude: l.latitude,
          longitude: l.longitude,
          accuracyM: l.accuracyM,
          capturedAt: l.capturedAt,
          lowAccuracyAccepted: l.lowAccuracyAccepted,
          roadAddress: l.roadAddress,
          parcelAddress: l.parcelAddress,
          regionCode: l.regionCode,
          regionSido: l.regionSido,
          regionSigungu: l.regionSigungu,
        }
      : null,
  };
}
export function sameWrite(a: TransactionWrite, b: TransactionWrite) {
  const clean = (value: string | null) => value?.trim() || null;
  const normalize = (x: TransactionWrite) => {
    const l = x.location;
    const road = clean(l?.roadAddress ?? null),
      parcel = clean(l?.parcelAddress ?? null);
    return [
      x.type,
      x.amount,
      x.currency,
      x.categoryId,
      x.transactionDate,
      clean(x.memo),
      l
        ? [
            l.source,
            clean(l.externalPlaceId),
            clean(l.placeName),
            Number(l.latitude).toFixed(6),
            Number(l.longitude).toFixed(6),
            l.accuracyM === null ? null : Number(l.accuracyM).toString(),
            l.capturedAt === null ? null : new Date(l.capturedAt).toISOString(),
            l.lowAccuracyAccepted,
            road,
            parcel,
            road || parcel ? l.regionCode : null,
            road || parcel ? clean(l.regionSido) : null,
            road || parcel ? clean(l.regionSigungu) : null,
          ]
        : null,
    ];
  };
  return JSON.stringify(normalize(a)) === JSON.stringify(normalize(b));
}
