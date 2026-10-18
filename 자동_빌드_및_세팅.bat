@echo off
chcp 65001 > nul
setlocal
title 마인크래프트 1.12.2 음성 커맨드 모드 (.jar) 원클릭 자동 생성기

echo ============================================================================
echo   [1/3] Gradle 빌드 도구 확인 및 자동 다운로드 중...
echo ============================================================================

if not exist ".gradle_local\gradle-7.6.4\bin\gradle.bat" (
    mkdir ".gradle_local" 2>nul
    echo Gradle 7.6.4 다운로드 중... (최초 1회만 진행됩니다)
    powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-7.6.4-bin.zip' -OutFile '.gradle_local\gradle.zip'"
    echo Gradle 압축 해제 중...
    powershell -Command "Expand-Archive -Path '.gradle_local\gradle.zip' -DestinationPath '.gradle_local' -Force"
)

echo.
echo ============================================================================
echo   [2/3] voicecommand-1.12.2-1.0.0.jar 모드 파일 빌드 중...
echo   (Forge 1.12.2 및 Vosk 음성 인식 엔진을 하나로 묶는 작업으로 2~5분가량 소요됩니다)
echo ============================================================================

call ".gradle_local\gradle-7.6.4\bin\gradle.bat" build --no-daemon

if not exist "build\libs\voicecommand-1.12.2-1.0.0.jar" (
    echo.
    echo [오류] 빌드 실패! PC에 Java(JDK 8 이상)가 설치되어 있는지 확인해주세요.
    echo PC에 Java를 설치하지 않고 빌드하려면 포함된 .github 폴더까지 GitHub에 올리시면 자동 빌드됩니다.
    pause
    exit /b 1
)

echo.
echo ============================================================================
echo   [3/3] 한국어 음성 모델(vosk-model-small-ko-0.22) 및 클라이언트 폴더 패키징 중...
echo ============================================================================

mkdir "완성된_클라이언트_적용폴더\mods" 2>nul
mkdir "완성된_클라이언트_적용폴더\config\voicecommand\model" 2>nul

copy /Y "build\libs\voicecommand-1.12.2-1.0.0.jar" "완성된_클라이언트_적용폴더\mods\voicecommand-1.12.2-1.0.0.jar" >nul
copy /Y "완성된_클라이언트_적용폴더_기본설정\commands.json" "완성된_클라이언트_적용폴더\config\voicecommand\commands.json" >nul

if not exist ".gradle_local\vosk-ko.zip" (
    echo Vosk 한국어 경량 모델 다운로드 중 (약 42MB)...
    powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip' -OutFile '.gradle_local\vosk-ko.zip'"
    powershell -Command "Expand-Archive -Path '.gradle_local\vosk-ko.zip' -DestinationPath '.gradle_local\vosk_extracted' -Force"
)

xcopy /E /I /Y ".gradle_local\vosk_extracted\vosk-model-small-ko-0.22\*" "완성된_클라이언트_적용폴더\config\voicecommand\model\" >nul

echo.
echo ============================================================================
echo   [완료!] '완성된_클라이언트_적용폴더' 안에 .jar 모드와 한국어 모델이 모두 준비되었습니다!
echo   해당 폴더 안의 'mods'와 'config' 폴더를 마인크래프트(.minecraft) 폴더에 복사하세요.
echo ============================================================================
explorer "완성된_클라이언트_적용폴더"
pause
