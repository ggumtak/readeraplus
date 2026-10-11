# ReaderaPlus — Claude Code 작업 규칙

Innospace One Comet(e-ink, Android 14)용 개인 전자책 리더. EPUB + TXT, Kotlin 2.1 / AGP 8.7 / minSdk 26.
**AndroidX·Material·Compose 없음**, UI는 코드로 만든다(`ui/kit/Ui.kt`). 유일한 의존성은 `kotlinx-coroutines-android`.

- 설계·규칙의 원문: `docs/ARCHITECTURE.md`(Ground rules), `docs/R3_INTERFACES.md`(현재 계약), `docs/next/README.md`(인수인계·진행 상황).
- 소스 경로는 `app/src/main/java/com/ggumtak/readeraplus/` 기준, 테스트는 `app/src/test/java/...`에 같은 경로로 둔다.
- 로컬 검사: `tools/typecheck.sh [--own <path>]`, `tools/unittest.sh [--own <path>] [fq.TestClass]` — `/opt/tc` 툴체인이 있을 때만
  동작한다. 없으면 CI(`.github/workflows/build.yml`, push마다 `testDebugUnitTest` + `assembleRelease`)가 유일한 검사다.
- 동결 contract 파일(명시적 지시 없이 수정 금지): `engine/Content.kt`, `engine/Layout.kt`, `format/BookDocument.kt`,
  `format/Documents.kt`, `settings/*`, `data/Models.kt`, `ui/kit/Ui.kt`, `reader/ReaderHost.kt`, `render/FontCatalog.kt`,
  `App.kt`, `AndroidManifest.xml`, `res/values/*`.
- 페이지 넘김은 즉시 교체(애니메이션 없음), e-ink UI는 흑백·무애니메이션. 성능 최우선(메인 스레드 작업·핫 루프 할당 금지).
- GPL/AGPL 리더 코드 복사 금지. 순수 로직에는 JVM 단위 테스트를 추가한다.

## 멀티 에이전트 운영 규칙

메인 에이전트(오케스트레이터)는 **Opus 5.5 / 높음**(`.claude/settings.json`)으로 기획·아키텍처 설계·분배·통합·최종 검증을
맡는다. 서브에이전트의 모델과 추론 수준은 각 파일의 전체 모델 ID `model:`와 `effort:`로 고정한다.

| 역할 | 에이전트 | 모델 / 추론 수준 | 권한 |
|---|---|---|---|
| 오케스트레이터 | 메인 | Opus 5.5 / 높음 | 설계·분배·통합·커밋, 직접 구현도 함 |
| 복잡한 로직 구현 | `logic-implementer` | Opus 5.5 / 높음 | 지정 파일만 수정 |
| UI 작업·일반 구현 | `ui-implementer` | Sonnet 5.5 / 높음 | 지정 파일만 수정 |
| 이미지 에셋(벡터 아이콘·SVG) | `asset-designer` | Sonnet 5.5 / 중간 | 새 drawable·SVG만 |
| 작업물 검토 | `code-reviewer` | Opus 5.5 / 높음 | 읽기 전용 |
| 코드 근거 수집 | `Explore` | Haiku 5.5 / 높음 | 읽기 전용 |
| 문서 발췌 | `doc-reader` | Haiku 5.5 / 중간 | 문서 읽기 전용 |
| 정해진 검사 실행 | `test-runner` | Haiku 5.5 / 중간 | 소스 수정 금지 |
| 정해진 순서로 촬영 | `screenshot-runner` | Haiku 5.5 / 중간 | 기존 환경에서 촬영만 |

모델 배정 기준(2026-10-11, 사용자와 GPT 의견을 합쳐 정함):
- **Opus**는 요구사항 해석, 설계, 원인이 불분명한 버그, 회귀 위험이 큰 로직, 최종 검토를 맡는다. 문제를 이미 이해한
  메인은 긴 설명서를 써서 넘기기보다 직접 고친다.
- **Sonnet**은 범위가 정해진 구현·버그 수정·테스트 작성을 맡는다. 에셋처럼 단순한 일만 중간으로 둔다.
- **Haiku는 코드를 쓰거나 판단하지 않는다.** "쉬워 보이는 일"이 아니라 "답을 확인하기 쉬운 일"만 맡긴다: 지정 범위
  검색, 원문·로그 발췌, 정해진 명령 실행, 정해진 순서의 촬영. 결론("문제없음")이 아니라 메인이 바로 확인할 근거
  (`path:line`·발췌·종료 코드·파일 경로)를 가져오게 한다. 실패하면 재시도하지 않고 멈춰서 보고한다.
- 명령 한 번이나 grep 한 번으로 끝나는 일은 서브에이전트 없이 메인이 직접 한다.
- 실제 비용은 사용료 + 지시·검수 + 실패 복구 시간이다. 보조 작업이 검수 수고를 줄이지 못하면 맡기지 않는다.
- 같은 작업이 두 번 실패하면 한 단계 위 담당(ui-implementer → logic-implementer → 메인)으로 넘긴다.
- Claude 모델은 래스터 이미지(PNG/JPG)를 그리지 못하므로 에셋은 VectorDrawable XML과 SVG로 만든다.
- AI 사전의 채팅 기본값(Haiku / 높음)과 개발 작업 모델 배정은 별개다.

### 위임 기준
- **메인이 직접:** 한두 파일의 수정, 이미 위치를 아는 조회, 명령 하나로 끝나는 확인, 아키텍처 설계, 원인이 불분명한
  복잡한 버그, 여러 모듈에 걸친 통합, 그리고 아래 핵심 영역의 설계 결정.
- **logic-implementer:** 핵심 영역(파싱·목차·화 인식, 페이지 이동, anchor, 가로 두 쪽 보기, 설정 저장)의 로직과
  동시성·캐시·데이터·AI 요청 로직.
- **ui-implementer:** 코드로 만드는 화면·대화상자·메뉴·팝업·설정 화면, AI 사전 페이지의 마크업·CSS, 일반 기능 구현과
  테스트 작성.
- **asset-designer:** 아이콘과 간단한 그림(VectorDrawable XML, 인라인 SVG).
- 구현 담당에는 메인이 수정 파일·인터페이스·완료 조건을 정한 뒤에만 위임한다. 프롬프트에 소유 파일 목록과 지켜야 할
  시그니처를 적는다. 실패한 테스트의 수정은 해당 구현 담당에 맡긴다.
- **Explore:** 여러 파일·디렉터리를 훑어야 근거가 모이는 탐색. 검색 대상·범위·가져올 항목을 지정하고, 메인은
  `path:line`과 발췌만 받는다.
- **doc-reader / test-runner / screenshot-runner:** 지정 문서의 발췌, 지정 명령의 실행 결과, 지정 순서의 촬영만 맡긴다.
  판단(무엇이 중요한지, 테스트가 충분한지, 화면이 이상한지)은 메인 또는 code-reviewer가 한다.
- **code-reviewer:** 구현 후 실행한다. 사소한 수정에는 생략할 수 있다.

### 병렬 실행
1. 서로 독립적인 작업만 병렬로, 동시에 보통 2~3개 이내.
2. 같은 파일을 두 에이전트가 동시에 수정하지 않는다. 수정 범위는 파일 단위로 분리한다(기존 레인 규칙과 동일:
   `docs/next/wave2/PLAN.md` §4). 공유 디렉터리(`--own reader`, `--own render` 등)로 검사하지 않는다.
3. 인터페이스(공개 시그니처)는 구현 위임 전에 메인이 정한다.
4. 모든 결과의 통합·커밋·푸시는 메인만 한다. 서브에이전트는 git 기록을 바꾸지 않는다.

### 핵심 영역 — 회귀 방지 최우선
EPUB/TXT 파싱과 목차·화 인식, 페이지 이동, 읽기 위치 저장·복원(anchor), 가로 두 쪽 보기, 사용자 설정 저장,
UI·렌더링(상태 표시줄 포함). 이 영역의 변경은 메인이 직접 설계·검토하고, 회귀 테스트를 추가하고, 최종 검증한다.

### 컨텍스트·사용량
- 서브에이전트는 요약만 반환한다(긴 로그·파일 내용 금지). 이미 읽은 파일을 다시 읽지 않는다.
- 작업 난이도에 맞는 모델을 쓰되, 안정성을 희생해서 비용을 줄이지 않는다. 구독 사용량은 서브에이전트도 함께 소모하므로
  불필요한 호출·과도한 병렬은 피한다.
- 서브에이전트 모델은 각 파일의 전체 모델 ID `model:`와 `effort: high`로 고정한다. 환경 변수나 호출 옵션으로 다른 모델·추론 수준이 강제되면 이를 확인하고 보고한다. 결과에서 다른 모델로 대체·폴백된 흔적이 보이면 사용자에게 보고한다.
