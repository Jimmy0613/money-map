"use client";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { zodResolver } from "@hookform/resolvers/zod";
import { api, ApiError, csrf } from "../shared/api";
import { useAuth } from "../shared/providers";
import { useRouter } from "next/navigation";
import Link from "next/link";
const schema = z.object({
  email: z.email("이메일 형식을 확인하세요.").max(254),
  password: z.string().min(10, "비밀번호는 10자 이상입니다.").max(64),
  passwordConfirmation: z.string(),
  termsAccepted: z.boolean(),
});
type Fields = z.infer<typeof schema>;
export function AuthForm({ signup = false }: { signup?: boolean }) {
  const [error, setError] = useState("");
  const [done, setDone] = useState(false);
  const router = useRouter();
  const auth = useAuth();
  const {
    register,
    handleSubmit,
    setError: fieldError,
    formState: { errors, isSubmitting },
  } = useForm<Fields>({
    resolver: zodResolver(schema),
    defaultValues: {
      email: "",
      password: "",
      passwordConfirmation: "",
      termsAccepted: false,
    },
  });
  async function submit(d: Fields) {
    setError("");
    if (signup) {
      if (!/[A-Za-z]/.test(d.password) || !/[0-9]/.test(d.password)) {
        fieldError("password", { message: "영문과 숫자를 각각 포함하세요." });
        return;
      }
      if (d.password !== d.passwordConfirmation) {
        fieldError("passwordConfirmation", {
          message: "비밀번호가 일치하지 않습니다.",
        });
        return;
      }
      if (!d.termsAccepted) {
        fieldError("termsAccepted", { message: "동의 여부를 확인하세요." });
        return;
      }
    }
    try {
      await csrf();
      await api(signup ? "/auth/signup" : "/auth/login", {
        method: "POST",
        body: JSON.stringify(
          signup
            ? { ...d, termsVersion: "v1" }
            : { email: d.email, password: d.password },
        ),
      });
      await csrf();
      if (signup) setDone(true);
      else {
        await auth.reload();
        router.replace("/");
      }
    } catch (e) {
      if (e instanceof ApiError)
        for (const f of e.fields) {
          if (
            [
              "email",
              "password",
              "passwordConfirmation",
              "termsAccepted",
            ].includes(f.field)
          )
            fieldError(f.field as keyof Fields, {
              type: "server",
              message: f.message,
            });
        }
      setError(
        e instanceof Error ? e.message : "연결을 확인하고 다시 시도하세요.",
      );
    }
  }
  return (
    <main id="content">
      <div className="hero">
        <p className="eyebrow">MONEY MAP</p>
        <h1>{signup ? "나의 소비 기록 시작하기" : "다시 만나 반가워요"}</h1>
        <p className="muted">어디에서 썼는지, 내 돈의 흐름을 한눈에.</p>
      </div>
      {done ? (
        <div className="card">
          <p>가입되었습니다. 로그인해 첫 거래를 기록하세요.</p>
          <Link href="/login">로그인</Link>
        </div>
      ) : (
        <form onSubmit={handleSubmit(submit)} className="card" noValidate>
          <label>
            이메일
            <input
              type="email"
              autoComplete="email"
              {...register("email")}
              aria-label="이메일"
              aria-describedby="auth-email-error"
              aria-invalid={!!errors.email}
            />
            <span id="auth-email-error" className="form-error">
              {errors.email?.message}
            </span>
          </label>
          <label>
            비밀번호
            <input
              type="password"
              aria-label="비밀번호"
              autoComplete={signup ? "new-password" : "current-password"}
              {...register("password")}
              aria-describedby="auth-password-error"
              aria-invalid={!!errors.password}
            />
            <small>영문·숫자 포함 10~64자</small>
            <span id="auth-password-error" className="form-error">
              {errors.password?.message}
            </span>
          </label>
          {signup && (
            <>
              <label>
                비밀번호 확인
                <input
                  type="password"
                  autoComplete="new-password"
                  {...register("passwordConfirmation")}
                  aria-label="비밀번호 확인"
                  aria-describedby="auth-passwordConfirmation-error"
                />
                <span
                  id="auth-passwordConfirmation-error"
                  className="form-error"
                >
                  {errors.passwordConfirmation?.message}
                </span>
              </label>
              <details>
                <summary>개발용 이용 안내 v1</summary>
                <p>
                  개인 수입·지출을 기록하는 개발용 서비스입니다. 위치는 선택
                  사항이며 지도·검색 사용 시 Kakao로 조회 정보가 전달됩니다.
                  탈퇴하면 활성 DB의 데이터가 삭제되고 백업은 최대 30일
                  보존됩니다. 운영 서비스 약관은 출시 전에 별도로 확정합니다.
                </p>
              </details>
              <label>
                <input
                  type="checkbox"
                  {...register("termsAccepted")}
                  aria-label="개발용 이용 안내에 동의합니다."
                  aria-describedby="auth-termsAccepted-error"
                />
                개발용 이용 안내에 동의합니다.
                <span id="auth-termsAccepted-error" className="form-error">
                  {errors.termsAccepted?.message}
                </span>
              </label>
            </>
          )}
          <p role="alert">{error}</p>
          <button disabled={isSubmitting}>
            {isSubmitting ? "처리 중…" : signup ? "회원가입" : "로그인"}
          </button>
          <p>
            <Link href={signup ? "/login" : "/signup"}>
              {signup ? "이미 계정이 있어요" : "처음이신가요? 회원가입"}
            </Link>
          </p>
        </form>
      )}
    </main>
  );
}
