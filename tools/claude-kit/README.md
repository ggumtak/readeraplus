# claude-kit — 다른 저장소에 넣는 Claude Code 모델 운영 설정

이 저장소에서 쓰는 Opus·Sonnet·Haiku 역할 분담을 앱 전용 내용 없이 정리한 공통 버전이다.
Claude Code는 저장소 안의 `CLAUDE.md`와 `.claude/`만 읽으므로, 적용하려는 저장소마다 넣어야 한다.

| 파일 | 들어갈 곳 |
|---|---|
| `agents/*.md` (7개) | 대상 저장소의 `.claude/agents/` |
| `settings.json` (메인 = Opus / 높음) | 대상 저장소의 `.claude/settings.json` |
| `CLAUDE-agents.md` (운영 규칙) | 대상 저장소의 `CLAUDE.md` 끝에 붙임 |

설치: `tools/claude-kit/install.sh /path/to/other-repo` — 같은 이름의 파일이 이미 있으면 덮어쓰지 않는다.
Claude Code 세션에서는 "readeraplus의 tools/claude-kit을 이 저장소에 설치해줘"라고 하면 된다.

각 저장소의 `CLAUDE.md`에 그 프로젝트의 검사 명령(lint·테스트), 수정 금지 파일, 회귀 위험이 큰 핵심 영역을 적어 두면
서브에이전트가 그 규칙을 따른다.
