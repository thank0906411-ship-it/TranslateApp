# TranslateApp

사이트 링크를 입력하면 본문 텍스트를 오프라인으로 번역해주는 개인용 Android 앱.

## 아키텍처

- `data/network` — URL → HTML 원문 다운로드 (OkHttp)
- `data/parser` — HTML → 순수 텍스트(문단 단위) 추출 (Jsoup)
- `data/model` — 데이터 클래스
- `translation` — 번역 엔진 인터페이스 + ML Kit 구현체 (온디바이스, 오프라인)
- `cache` — Room DB 기반 번역 결과 캐싱 (같은 URL 재번역 방지)
- `ui` — MainActivity (URL 입력 + 결과 표시 + 다음/이전 탐색, MVP 단계는 단일 화면)
- `utils` — 로깅, 파일 경로 유틸
- `test` — 번역 품질 휴리스틱 점검 (TranslationEvaluator, ConsistencyChecker)

## 다음/이전 탐색 기능

브라우저의 뒤로/앞으로 가기와 비슷하게 동작합니다.

- **이전**: 항상 앱 안에 저장된 방문 기록에서 즉시 불러옵니다. 네트워크를 다시 타지 않아 빠릅니다.
- **다음**: 이미 가본 적 있는 다음 기록이 있으면 그쪽을 먼저 보여주고, 방문 기록의 맨 끝이면
  현재 페이지 HTML에서 `HtmlTextExtractor.findNextPageUrl()`로 실제 "다음 화/다음 페이지" 링크를
  찾아 자동으로 이동+번역합니다. `rel="next"`가 있는 사이트는 정확히 잡히고, 없는 사이트는
  "다음", "next", "›" 같은 텍스트를 가진 링크를 휴리스틱으로 찾으므로 사이트에 따라 실패할 수
  있습니다 — 실패하면 토스트로 알려줍니다.
- 방문 중 "이전"으로 되돌아간 뒤 새 URL을 입력하거나 다른 다음 링크로 이동하면, 그 이후의
  기존 기록은 잘라내고 새 기록으로 갈라집니다 (일반 브라우저와 동일한 동작).

## 빌드 전 필요한 것

1. JDK 17
2. Android SDK (Command line tools만 설치해도 무방, platform-tools + platforms;android-34 + build-tools;34.0.0)
3. `local.properties`의 `sdk.dir`을 본인 SDK 경로로 수정

## Gradle Wrapper에 대해

이 저장소에는 `gradle-wrapper.properties`만 포함되어 있고, 실행 스크립트(`gradlew`, `gradlew.bat`)와
`gradle-wrapper.jar`는 포함되어 있지 않습니다. 아래 둘 중 하나로 생성하세요.

- 로컬에 Gradle이 설치되어 있다면: `gradle wrapper --gradle-version 8.7`
- 또는 이 프로젝트를 Android Studio로 한 번 열면 자동 생성됩니다 (이후엔 다시 VS Code로 작업 가능)

## 빌드 & 실행

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 배포용(release) APK 빌드

```bash
./gradlew assembleRelease
```

`app/build.gradle.kts`의 `signingConfigs`에 본인 keystore를 등록한 뒤 빌드하면
서명된 APK가 `app/build/outputs/apk/release/`에 생성됩니다. (이 템플릿에는
개인 keystore가 없으므로 signingConfig는 직접 추가해야 합니다.)

## 앱 내 업데이트(GitHub Releases 기반)

Play Store 없이도 앱 안에서 "업데이트 확인" 버튼으로 새 버전을 받을 수 있다.
`update/AppUpdateChecker.kt`가 GitHub Releases API(`/releases/latest`)를 호출해
현재 설치된 `versionCode`보다 새 버전이 있으면 APK를 다운로드하고 설치 화면을 띄운다.

### 새 버전 배포 절차

1. `app/build.gradle.kts`에서 `versionCode`를 올리고 `versionName`도 갱신
2. `./gradlew assembleRelease`로 서명된 APK 빌드
3. GitHub Release 생성 — **태그 이름에 반드시 versionCode와 같은 숫자가 포함되어야 한다**
   (예: versionCode 2라면 태그는 `v2` 또는 `2`)
4. 빌드된 `.apk` 파일을 Release 에셋으로 첨부 (release 하나에 `.apk`는 하나만)

```bash
gh release create v2 app/build/outputs/apk/release/app-release.apk \
  --title "v2" --notes "변경 내역"
```

이렇게 올리면 기존 v1 사용자가 앱에서 "업데이트 확인"을 누르는 순간 v2를 감지하고
다운로드/설치를 제안한다.

## 다음 확장 방향

- 언어 자동 감지 (현재는 en→ko 고정)
- ML Kit → NLLB-200 등 커스텀 온디바이스 모델로 교체 (translation/ 폴더에 새 구현체만 추가하면 됨)
- UI를 Fragment 단위(UrlInputScreen / ResultScreen)로 분리
