# Simple Cast - Universal Smart TV & LG TV Cast App (v2.3)

![Simple Cast Icon](app/src/main/res/drawable/app_icon.jpg)

**Simple Cast** е съвременно, високопроизводително Android приложение, разработено на **Kotlin + Jetpack Compose**, предназначено за предаване на уеб видеа, локална галерия, IPTV канали и екранно огледало към **LG webOS TV, Android TV, Google TV, Philips, Sony, Samsung Tizen, TCL, Xiaomi TV Box и Chromecast**.

---

## 🚀 Какво е новото във версия v2.3

* 📺 **Пълна поддръжка за IPTV & Web Cast на Android TV (Philips / Sony / TCL)**: Добавени са стриктни `.m3u8` и `.ts` разширения при проксирането на стриймове (`/proxy/stream.m3u8?url=...`), изчистени са DLNA хедърите за HLS и са добавени пълен набор от HLS DIDL-Lite манифести, премахвайки грешката *"Форматът не се поддържа"*.
* 🔄 **Хардуерно завъртане на вертикални видеа за Android TV**: Вграден `VideoRotationTranscoder`, който използва хардуерния `MediaCodec` Surface енкодер на телефона за мигновено завъртане на вертикално записани телефонни видеа (90°/270°) с orientation 0, за да не се възпроизвеждат настрани на Android TV плейъри.
* 📐 **Защита от деформация / разтягане на картината**: Добавено динамично изчисляване на съотношението (`<upnp:aspectRatio>`) и подаване на точна резолюция, така че видеа с малка резолюция (напр. 360x640) да запазват оригиналната си геометрия и да не се разпъват по екрана.
* 🖼️ **Видео Thumbnail Preview в Gallery таба**: Интегриран динамичен екстрактор на видео кадри (`MediaMetadataRetriever`), който показва реална миниатюра на избраното видео под централния Play бутон.
* 🏷️ **Динамично име на устройството в Mini Player-а**: Долната контролна лента показва точното име на свързания телевизор (напр. *Playing on 50PUS7304/12*).
* 🌐 **Универсализиран интерфейс**: Всички системни текстове и инструкции са адаптирани за всички марки Smart TV и Android TV.

---

## 🌟 Основни Модули и Функционалности

### 1. 🖼️ Local Gallery Cast
* **SSDP Multicast Discovery**: Автоматично открива налични телевизори в същата Wi-Fi мрежа (UDP 239.255.255.250:1900).
* **Local HTTP Server**: Вграден NanoHTTPD сървър (`http://<PHONE_IP>:8080/media/<id>`) с поддръжка за HTTP Range (bytes=X-Y) за стрийминг на снимки и видеа от телефона.
* **Правилна ориентация**: Видеата от галерията се предават с правилно съотношение на страните без завъртане.

### 2. 🌐 Web Video Cast (Media Sniffer + Anti-AdBlock)
* **Media Sniffer**: Автоматично прихваща `.m3u8`, `.mp4`, `.mpd`, `.webm` видео адреси от уеб страници.
* **Ad Blocker & Anti-AdBlock Defuser**: Блокира рекламни домейни и изчиства анти-адблок банерите.
* **HTTPS Proxy & HLS Rewriter**: Пренаписва сегментите на `.m3u8` манифестите през локалния сървър за телевизори, които не поддържат HTTPS DLNA.

### 3. 📺 Free IPTV Web Portal
* Достъп с един бутон (**Free IPTV**) до интерактивен каталог с канали и филми.
* Вградена бърза търсачка по заглавие и категория.
* Вграден плейър за преглед на телефона + директен бутон **▶ Play & Cast Stream** към телевизора.

### 4. 📱 Screen Share (Screen Mirroring)
* **MediaProjection API**: Заснемане на екрана и звука на телефона в реално време (Foreground Service).
* **Miracast / Screen Share**: Бърз достъп до системните настройки за безжичен дисплей (`Settings.ACTION_CAST_SETTINGS`).

### 5. 🎨 Jetpack Compose UI
* Модерен тъмен интерфейс с LG Velvet Red & Neon Cyan акценти.
* Заключена вертикална ориентация (Portrait mode) за максимално удобство.
* Активен контролен мини-плейър (Play/Pause/Stop/Disconnect).

---

## 📥 Изтегляне (Download APK)

Можете да изтеглите готовата компилирана версия директно от репозиторито:
* 📦 **[SimpleCast-v2.3-debug.apk](SimpleCast-v2.3-debug.apk)**

---

## 🛠️ Сглобяване от сорс код (Build & Run)

```bash
# Клониране на хранилището
git clone https://github.com/Stoyan377/SimpleCast.git
cd SimpleCast

# Сглобяване на debug APK с Gradle
./gradlew assembleDebug
```

Сглобеният APK файл се създава в `app/build/outputs/apk/debug/app-debug.apk`.
