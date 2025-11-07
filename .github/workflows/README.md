# GitHub Actions Workflows

이 프로젝트는 GitHub Actions를 사용하여 자동으로 APK를 빌드합니다.

## 🚀 워크플로우

### 1. Build Android APK (`build-apk.yml`)

**트리거:**
- `claude/**` 브랜치에 push
- `main`, `develop` 브랜치에 push
- Pull Request (main으로)
- 수동 실행 (workflow_dispatch)

**동작:**
- JDK 17 설정
- Gradle 캐시 활용
- Debug APK 빌드
- APK를 Artifact로 업로드 (30일 보관)

**APK 다운로드 방법:**
1. GitHub 저장소 → Actions 탭
2. 최신 워크플로우 실행 선택
3. Artifacts 섹션에서 `app-debug` 다운로드

### 2. Build and Release APK (`release-apk.yml`)

**트리거:**
- Git 태그 push (`v*` 형식, 예: v1.0.0)
- 수동 실행 (버전 번호 입력)

**동작:**
- Debug APK 빌드
- 버전 이름으로 APK 리네임
- APK를 Artifact로 업로드 (90일 보관)
- GitHub Release 자동 생성 (태그인 경우)

**릴리즈 생성 방법:**
```bash
# 로컬에서 태그 생성
git tag v1.0.0
git push origin v1.0.0

# GitHub Actions가 자동으로:
# 1. APK 빌드
# 2. Release 페이지 생성
# 3. APK 첨부
```

## 📦 APK 다운로드

### 방법 1: Artifacts (자동 빌드)

1. **저장소 페이지** → **Actions** 탭
2. 최신 성공한 워크플로우 선택
3. **Artifacts** 섹션 스크롤
4. **app-debug** 또는 **CustomBrowser-Release** 다운로드

### 방법 2: Releases (태그 빌드)

1. **저장소 페이지** → **Releases**
2. 최신 릴리즈 선택
3. **Assets** 섹션에서 APK 다운로드

### 방법 3: 수동 트리거

1. **Actions** 탭 → **Build and Release APK**
2. **Run workflow** 버튼
3. 버전 번호 입력 (예: 1.0.0)
4. **Run workflow** 실행
5. 완료 후 Artifacts에서 다운로드

## 🔧 빌드 환경

- **OS:** Ubuntu Latest
- **JDK:** Temurin 17
- **Gradle:** Wrapper (8.2)
- **Cache:** Gradle 의존성 자동 캐시

## ⏱️ 예상 빌드 시간

- 첫 빌드: ~5-8분 (의존성 다운로드)
- 이후 빌드: ~2-3분 (캐시 활용)

## 📊 빌드 상태 확인

저장소 README에 배지 추가:

```markdown
![Build Status](https://github.com/Spica910/custombrowser/workflows/Build%20Android%20APK/badge.svg)
```

## 🎯 사용 예시

### 개발 중 (자동)
```bash
git add .
git commit -m "Add new feature"
git push origin claude/kiwi-browser-chrome-extension-011CUsj7cNTH7pY7jyY4zJmA
# → Actions 자동 실행, APK 빌드
```

### 릴리즈 (태그)
```bash
# 버전 태그 생성
git tag -a v1.0.0 -m "Release version 1.0.0"
git push origin v1.0.0
# → Release APK 빌드 및 GitHub Release 생성
```

### 수동 빌드
1. GitHub → Actions
2. "Build and Release APK" 선택
3. "Run workflow" 클릭
4. 버전 입력 → Run

## 📝 참고사항

- APK는 **Debug** 빌드입니다 (서명되지 않음)
- 실제 배포용은 **Release** 빌드 + 서명 필요
- Artifacts는 설정된 기간 후 자동 삭제됨
  - Debug: 30일
  - Release: 90일

## 🔐 Release APK 서명 (선택사항)

프로덕션 배포를 위해서는 서명이 필요합니다:

1. **Keystore 생성**
2. **GitHub Secrets에 추가:**
   - `KEYSTORE_FILE` (base64 인코딩)
   - `KEYSTORE_PASSWORD`
   - `KEY_ALIAS`
   - `KEY_PASSWORD`
3. `release-apk.yml` 수정하여 서명 단계 추가

---

**문제 해결:**
- 빌드 실패 시 Actions 로그 확인
- Gradle 캐시 문제: workflow 재실행
- 의존성 문제: `./gradlew clean` 추가
