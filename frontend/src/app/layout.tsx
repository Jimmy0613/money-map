import type { Metadata } from "next";
import { Providers } from "../shared/providers";
import "./styles.css";
export const metadata: Metadata = {
  title: "Money Map · 소비 위치 가계부",
  description: "수입과 지출, 소비한 장소를 함께 기록하세요.",
};
export default function Layout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko">
      <body>
        <a className="skip" href="#content">
          본문으로 이동
        </a>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
