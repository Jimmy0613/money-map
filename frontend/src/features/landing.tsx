import Link from "next/link";

export function Landing() {
  return (
    <main id="content" className="landing">
      <section className="landing-hero">
        <div className="landing-copy">
          <p className="eyebrow">기록에 장소를 더하다</p>
          <h1>
            어디에서 썼는지,
            <br />
            지도로 돌아보세요.
          </h1>
          <p className="landing-description">
            매일의 수입과 지출부터 소비한 장소까지.
            <br />
            흩어진 기록을 내역, 지도, 통계로 연결하는 가계부입니다.
          </p>
          <Link href="/login" className="button landing-cta">
            체험하기 <span aria-hidden="true">→</span>
          </Link>
          <p className="landing-note">
            회원가입 없이 공용 체험 계정으로 이용할 수 있어요.
          </p>
          <p className="landing-server-note">
            소개를 보는 동안 첫 연결을 미리 준비합니다.
            <br />
            무료 서버 특성상 처음에는 잠시 기다릴 수 있습니다.
          </p>
        </div>
        <div
          className="landing-preview"
          aria-label="가상 기록으로 구성한 소비 지도 예시"
        >
          <div className="preview-heading">
            <span>MY MONEY MAP</span>
            <span>가상 기록 예시</span>
          </div>
          <div className="preview-total">
            <span>이번 달 지출</span>
            <strong>
              53,800<span>원</span>
            </strong>
          </div>
          <svg className="preview-map" viewBox="0 0 480 310" aria-hidden="true">
            <rect width="480" height="310" fill="#eaf0e4" />
            <path
              d="M-20 210C100 135 160 290 285 210S410 125 510 160"
              stroke="#c5dedc"
              strokeWidth="48"
              fill="none"
            />
            <path
              d="M65-20L145 340M290-20L220 340M420-20L365 340M-20 72L510 120M-20 252L510 35M-20 285L510 290"
              stroke="#fffdf7"
              strokeWidth="21"
              fill="none"
            />
            <path
              d="M140 100Q285 100 275 218T390 180"
              stroke="#91ad8b"
              strokeWidth="3"
              strokeDasharray="6 7"
              fill="none"
            />
            {[
              [140, 95],
              [275, 215],
              [390, 175],
            ].map(([x, y]) => (
              <g key={x} transform={`translate(${x} ${y})`}>
                <path
                  d="M0 12C-4 5-17-7-17-18a17 17 0 1 1 34 0C17-7 4 5 0 12Z"
                  fill="#176546"
                />
                <circle cy="-18" r="6" fill="white" />
              </g>
            ))}
          </svg>
          <div className="preview-record">
            <span className="preview-record-icon" aria-hidden="true">
              ₩
            </span>
            <div>
              <strong>점심 식사</strong>
              <span>식비 · 서울시청 주변</span>
            </div>
            <strong>12,500원</strong>
          </div>
        </div>
      </section>
      <section className="landing-features" aria-label="Money Map의 주요 기능">
        {[
          [
            "01",
            "가볍게 기록하고",
            "금액과 분류, 날짜부터. 장소는 원할 때만 추가하세요.",
          ],
          [
            "02",
            "지도에서 돌아보고",
            "내 소비가 모인 장소와 주변의 지출을 살펴보세요.",
          ],
          [
            "03",
            "흐름을 이해하세요",
            "월별 수입·지출과 카테고리별 소비를 비교해 보세요.",
          ],
        ].map(([n, title, body]) => (
          <article key={n}>
            <span className="eyebrow">{n}</span>
            <h2>{title}</h2>
            <p>{body}</p>
          </article>
        ))}
      </section>
      <p className="landing-footer">
        공용 체험 계정의 기록은 방문자와 공유됩니다. 실제 개인 정보나 소비
        위치는 입력하지 마세요.
      </p>
    </main>
  );
}
