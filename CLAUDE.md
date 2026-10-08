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

메인 에이전트(Opus)는 설계·판단·분배·통합·최종 검증을 맡고, 독립적인 작업은 `.claude/agents/`의 서브에이전트에 맡긴다.

| 에이전트 | 모델 | 역할 | 권한 |
|---|---|---|---|
| `Explore` | haiku | 파일·심볼·호출부 탐색, 기존 구현·의존성 조사 (내장 Explore 대체) | 읽기 전용 |
| `implementer` | sonnet | 메인이 설계한 범위가 명확한 구현·버그 수정 + 단위 테스트 | 지정 파일만 수정 |
| `test-runner` | haiku | typecheck/unittest/CI 결과 실행·요약 | 소스 수정 금지 |
| `code-reviewer` | sonnet | 변경분의 버그·회귀·성능·안정성 리뷰 | 읽기 전용 |

### 위임 기준
- **메인이 직접:** 한두 파일의 간단한 수정, 이미 위치를 아는 단일 조회, 아키텍처 설계, 원인이 불분명한 복잡한 버그,
  여러 모듈에 걸친 통합, 그리고 아래 핵심 영역의 설계 결정.
- **Explore:** 여러 파일·디렉터리를 훑어야 결론이 나는 탐색. 메인은 결론과 `path:line`만 받는다.
- **implementer:** 메인이 수정 파일·인터페이스·완료 조건을 정한 뒤에만 위임한다. 프롬프트에 소유 파일 목록과
  지켜야 할 시그니처를 적는다.
- **test-runner / code-reviewer:** 구현 후 둘을 병렬로 실행한다. 사소한 수정에는 생략할 수 있다.

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
- 서브에이전트 모델은 각 파일의 `model:`로 고정한다. 결과에서 다른 모델로 대체·폴백된 흔적이 보이면 사용자에게 보고한다.
