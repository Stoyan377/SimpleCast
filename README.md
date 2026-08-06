# Simple Cast - Android LG TV Cast & Screen Mirroring App

Simple Cast е съвременно Android приложение, разработено на **Kotlin + Jetpack Compose**, предназначено за предаване на съдържание (мултимедия и екран) към LG TV (webOS 4.5+ / DLNA / Miracast).

![Simple Cast Icon](app/src/main/res/drawable/app_icon.jpg)

## 🌟 Основни Модули и Функционалности

1. **Local Gallery Cast (SSDP + DLNA + Local HTTP Server)**
   - **SSDP Discovery**: Автоматично откриване на LG TV (MediaRenderer) във Wi-Fi мрежата през UDP Multicast (239.255.255.250:1900).
   - **Local HTTP Server**: Вграден NanoHTTPD сървър (`http://<PHONE_IP>:8080/media/<id>`) с пълна поддръжка за HTTP Range (bytes=X-Y) за стрийминг на видео от галерията на телефона.
   - **DLNA SOAP Controller**: Изпращане на UPnP AVTransport команди (`SetAVTransportURI`, `Play`, `Pause`, `Stop`, `Seek`) с DIDL-Lite XML метаданни.

2. **Web Video Cast (WebView Sniffer)**
   - **In-App Browser**: Вграден уеб браузър с адресна лента и бързи отметки.
   - **Media Sniffer**: `MediaSnifferWebViewClient` прихваща HTTP/HTTPS заявки (`shouldInterceptRequest`) за директни видео потоци (`.mp4`, `.m3u8`, `.mpd`, `.webm`).
   - **Direct Cast**: Възможност за пускане на избрания видео URL директно към телевизора.

3. **Screen Mirroring (Screen Share)**
   - **MediaProjection API**: Прихващане на екрана и аудиото на телефона в реално време (Foreground Service).
   - **Miracast / LG Screen Share**: Директно отваряне на системния диалог за безжичен дисплей (`Settings.ACTION_CAST_SETTINGS`).

4. **Jetpack Compose UI**
   - Модерен тъмен интерфейс с LG Velvet Red & Neon Cyan акценти.
   - 3 основни раздела: **[ Gallery ]** | **[ Web Cast ]** | **[ Screen Share ]**.
   - Панел за намерени устройства във Wi-Fi мрежата и мини плейър за контрол на възпроизвеждането.

## 📱 Сглобяване (Build & Run)

```bash
# Клониране на репозиторито
git clone https://github.com/Stoyan377/SimpleCast.git
cd SimpleCast

# Сглобяване на debug APK
./gradlew assembleDebug
```

Инсталационният APK файл се намира в `app/build/outputs/apk/debug/app-debug.apk`.
