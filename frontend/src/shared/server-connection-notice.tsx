export type ConnectionState = "connecting" | "ready" | "failed";

export function ServerConnectionNotice({
  state,
  elapsed,
  retry,
}: {
  state?: ConnectionState;
  elapsed: number;
  retry: () => void;
}) {
  if (!state) return null;
  return (
    <section
      className={`connection-notice connection-${state}`}
      aria-label="서비스 연결 상태"
    >
      <div role="status">
        <strong>
          {state === "ready"
            ? "서버가 준비됐어요"
            : state === "failed"
              ? "연결을 다시 확인해 주세요"
              : elapsed >= 120
                ? "예상보다 준비가 늦어지고 있어요"
                : "체험 서버를 준비하고 있어요"}
        </strong>
        <p>
          {state === "ready"
            ? "이제 로그인해 서비스를 둘러볼 수 있습니다."
            : state === "failed"
              ? "서버에 연결하지 못했습니다. 잠시 후 다시 연결하거나 소개 화면으로 돌아갈 수 있어요."
              : "무료 서버를 사용해 첫 연결에 1~2분 정도 걸릴 수 있습니다. 준비되면 자동으로 알려드릴게요."}
        </p>
      </div>
      {state === "connecting" && (
        <p className="connection-clock" aria-live="off">
          기다린 시간 {String(Math.floor(elapsed / 60)).padStart(2, "0")}:
          {String(elapsed % 60).padStart(2, "0")}
        </p>
      )}
      {state === "failed" && (
        <button type="button" className="quiet" onClick={retry}>
          다시 연결
        </button>
      )}
    </section>
  );
}
