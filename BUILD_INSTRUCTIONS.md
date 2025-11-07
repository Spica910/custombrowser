# Custom Browser - 빌드 및 설치 가이드

## 📋 목차
1. [준비사항](#준비사항)
2. [빌드 방법](#빌드-방법)
3. [설치 방법](#설치-방법)
4. [문제 해결](#문제-해결)

---

## 준비사항

### 필수 소프트웨어
- **JDK 17** 이상
- **Android Studio** Hedgehog (2023.1.1) 이상
- **Android SDK 34**
- **Gradle 8.2** (자동 설치됨)

### Android Studio 설치
1. [Android Studio 다운로드](https://developer.android.com/studio)
2. 설치 후 SDK Manager에서 다음 설치:
   - Android SDK Platform 34
   - Android SDK Build-Tools 34.0.0
   - Android SDK Command-line Tools

### 환경 변수 설정 (선택사항 - CLI 빌드용)
```bash
# Windows (PowerShell)
$env:ANDROID_HOME = "C:\Users\YourName\AppData\Local\Android\Sdk"

# Mac/Linux
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools
```

---

## 빌드 방법

### 방법 1: Android Studio GUI (가장 쉬움) ⭐

1. **프로젝트 열기**
   - Android Studio 실행
   - `File` → `Open`
   - `custombrowser` 폴더 선택

2. **Gradle 동기화**
   - 자동으로 시작됨
   - 또는 `File` → `Sync Project with Gradle Files`

3. **APK 빌드**
   - `Build` → `Build Bundle(s) / APK(s)` → `Build APK(s)`
   - 빌드 완료 후 알림 클릭하여 APK 위치 확인

4. **APK 위치**
   ```
   custombrowser/app/build/outputs/apk/debug/app-debug.apk
   ```

### 방법 2: 명령줄 빌드

#### Windows (PowerShell)
```powershell
cd custombrowser
.\gradlew.bat assembleDebug
```

#### Mac/Linux
```bash
cd custombrowser
chmod +x build-apk.sh
./build-apk.sh
```

또는 직접:
```bash
chmod +x gradlew
./gradlew assembleDebug
```

#### 빌드 완료 후
```
✅ APK 생성 위치:
   app/build/outputs/apk/debug/app-debug.apk

📦 파일 크기: 약 5-10MB
```

---

## 설치 방법

### 방법 1: USB 케이블로 직접 설치

1. **갤럭시 탭 설정**
   - 개발자 옵션 활성화
     - 설정 → 휴대전화 정보 → 소프트웨어 정보
     - "빌드 번호"를 7번 터치
   - USB 디버깅 활성화
     - 설정 → 개발자 옵션 → USB 디버깅 켜기

2. **USB 연결**
   - USB 케이블로 PC와 갤럭시 탭 연결
   - 탭에서 "USB 디버깅 허용" 승인

3. **설치 (명령줄)**
   ```bash
   # 기기 연결 확인
   adb devices

   # APK 설치
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

4. **설치 (Android Studio)**
   - Run 버튼 (▶️) 클릭
   - 연결된 기기 선택
   - 자동 설치 및 실행

### 방법 2: APK 파일 직접 전송

1. **APK 파일 복사**
   ```
   app/build/outputs/apk/debug/app-debug.apk
   → 갤럭시 탭의 Download 폴더로 복사
   ```

2. **전송 방법**
   - USB 케이블: PC → 갤럭시 탭 (파일 탐색기)
   - Google Drive: 업로드 후 탭에서 다운로드
   - 이메일: 자신에게 첨부파일로 전송

3. **갤럭시 탭에서 설치**
   - 파일 앱 → Download 폴더
   - `app-debug.apk` 터치
   - "알 수 없는 앱 설치 허용" → 설정 → 허용
   - 설치 버튼 터치

### 방법 3: 무선 설치 (WiFi ADB)

1. **갤럭시 탭 설정**
   - 개발자 옵션 → 무선 디버깅 활성화
   - IP 주소 및 포트 확인 (예: 192.168.1.100:5555)

2. **PC에서 연결**
   ```bash
   adb connect 192.168.1.100:5555
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

---

## 문제 해결

### 빌드 오류

#### "SDK location not found"
```bash
# local.properties 파일 생성 (프로젝트 루트에)
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# Windows
echo sdk.dir=C:\\Users\\YourName\\AppData\\Local\\Android\\Sdk > local.properties
```

#### "Gradle version too old"
```bash
# Gradle 재생성
gradle wrapper --gradle-version 8.2
```

#### "Build failed: Network timeout"
- 네트워크 연결 확인
- VPN 끄기
- Gradle 캐시 삭제:
  ```bash
  rm -rf ~/.gradle/caches
  ./gradlew clean build
  ```

#### "Java version incompatible"
```bash
# Java 버전 확인
java -version

# JDK 17 필요
# 설치: https://www.oracle.com/java/technologies/downloads/
```

### 설치 오류

#### "App not installed"
- 이전 버전 삭제 후 재설치
- 저장 공간 확인 (최소 50MB 필요)

#### "Parse error"
- APK 파일 손상 → 재빌드
- Android 버전 확인 (최소 Android 7.0 필요)

#### "USB 디버깅 안 됨"
- USB 케이블 재연결
- 다른 USB 포트 사용
- USB 디버깅 재활성화

---

## 빠른 참조

### 전체 빌드 프로세스 (CLI)
```bash
# 1. 프로젝트 진입
cd custombrowser

# 2. 클린 빌드
./gradlew clean

# 3. Debug APK 빌드
./gradlew assembleDebug

# 4. 설치 (기기 연결됨)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 5. 앱 실행
adb shell am start -n com.custombrowser/.MainActivity
```

### Gradle 태스크
```bash
./gradlew tasks                    # 모든 태스크 보기
./gradlew assembleDebug           # Debug APK
./gradlew assembleRelease         # Release APK (서명 필요)
./gradlew installDebug            # Debug 빌드 & 설치
./gradlew clean                   # 빌드 파일 삭제
```

### ADB 명령어
```bash
adb devices                       # 연결된 기기 목록
adb install app-debug.apk        # 설치
adb uninstall com.custombrowser  # 제거
adb logcat                       # 로그 보기
adb shell pm list packages       # 설치된 앱 목록
```

---

## 성공적인 빌드 후

### APK 정보 확인
```bash
# APK 크기
ls -lh app/build/outputs/apk/debug/app-debug.apk

# APK 내용 확인
unzip -l app/build/outputs/apk/debug/app-debug.apk
```

### 서명된 Release APK 만들기 (배포용)
1. Android Studio: Build → Generate Signed Bundle / APK
2. 키스토어 생성 또는 선택
3. Release variant 선택
4. 빌드 완료

---

## 추가 리소스

- [Android Developer Documentation](https://developer.android.com/studio/build)
- [Gradle Build Guide](https://docs.gradle.org/current/userguide/userguide.html)
- [ADB Documentation](https://developer.android.com/studio/command-line/adb)

---

## 요약

**가장 쉬운 방법:**
1. Android Studio로 프로젝트 열기
2. Build → Build APK
3. APK를 갤럭시 탭으로 복사
4. 탭에서 파일 열어 설치

**필요한 것:**
- JDK 17
- Android Studio
- USB 케이블 (또는 파일 전송 수단)

**예상 시간:**
- 첫 빌드: 5-10분
- 이후 빌드: 1-2분

---

**Made with ❤️ for Galaxy Tab Users**
