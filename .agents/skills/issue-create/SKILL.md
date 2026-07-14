---
name: issue-create
description: docs/ 기준 문서에서 작업을 도출해 GitHub 이슈를 생성한다(gh CLI). 
  문서 기반 이슈 + API별 1이슈 단위로 분해하고, 완료 조건은 검증 가능한 형태로 작성한다. 
  사용자가 "이슈 만들어줘", "이슈 생성"처럼 이슈 생성을 요청할 때 사용한다.
---

## 기본 규칙

이슈 생성 본문 구조의 정본은 기능 생성에는 `.github/ISSUE_TEMPLATE/feature.md`, 버그 이슈 생성에는 `.github/ISSUE_TEMPLATE/bug.md`이다.

- 완료 조건은 반드시 **상태코드·경로·명령어 수준**으로 검증 가능해야 한다.
- API 이슈의 작업 내용은 수직 슬라이스(Entity→Repository→Service→Controller→Test) 전체를 담는다 — 계층 쪼개기 금지.
- 이슈 하나에 API 두 개를 묶지 않는다.
- 요구사항에 없는 작업을 이슈로 만들지 않는다.

## 절차

1. **문서 읽기** — 대상 작업이 걸친 docs 섹션을 읽는다. 문서에 없는 내용을 지어내지 않는다.
2. **라벨 확보** — `gh label list`로 확인, 없으면 생성: `gh label create feat --color 0e8a16` 등 (`feat`·`fix`·`docs`·`refactor`·`test`·`chore`).
3. **생성** — 확인 후 `gh issue create --title "..." --body "..." --label "..."`. 본문은 임시 파일로 작성해 `--body-file`로 넘긴다 (PowerShell 이스케이프 사고 방지).

## 완료 기준

- [ ] 모든 이슈에 docs 근거 링크 존재
- [ ] 모든 완료 조건이 검증 가능한 문장
- [ ] 사용자 확인 후 생성함
- [ ] 이슈 번호·URL 보고함