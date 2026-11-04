# Devamsızlık Takibi (Android + iOS)

İTÜ ders programına göre her dersin bitiminde **"MAK 335 dersine girdin mi?"** diye bildirim gönderen,
devamsızlıklarını sayan ve hakkı biten dersleri uyaran uygulama. Android sürümü repo kökünde (`app/`),
iPhone sürümü `ios/` klasöründe; ikisinin özellikleri aynı.

## Kurulum (Android)
1. Repodaki **Releases → latest** altından `Devamsizlik.apk` dosyasını telefona indir
   (ya da Actions sekmesindeki son çalışmanın `Devamsizlik-apk` çıktısını).
2. Dosyayı aç, "bilinmeyen kaynaklardan yüklemeye" izin ver ve yükle.
3. Uygulamayı bir kez aç ve **bildirim iznini ver**.

## Özellikler
- **Ders bitiminde bildirim**: *Evet / Hayır / Ders yapılmadı* tuşları. Tuşa basınca uygulamayı açmadan kaydedilir.
- **Derste birden fazla yoklama**: Ders başına yoklama sayısı (1–4) ayarlanabilir. 2 yoklamalı 2 saatlik derste
  ilk soru dersin başlamasından 1 saat sonra, ikincisi ders bitiminde gelir; her saat ayrı sayılır.
- **Bildirimden sonra hatırlatma**: Cevap vermez ya da bildirimi kapatırsan 15 / 30 / 60 dk sonra bir kez daha sorar.
- **Haftalık özet**: Seçtiğin gün ve saatte (varsayılan Pazartesi 08:00) son 7 günün özeti ve riskli dersler.
- **Erken uyarı**: "Hayır" deyince kalan hakkın 1 (ya da seçtiğin sayıya) inerse hemen uyarır; ayrıca hakkı azalan
  derslerden 30 dk / 1 saat / 2 saat önce "kaçırma" hatırlatması gönderir.
- **Programı fotoğraftan ekleme**: Uygulamadaki hazır metni kopyala, ders programının fotoğrafıyla birlikte
  ChatGPT / Gemini / Claude gibi bir yapay zekâya gönder, cevabı uygulamaya yapıştır. Uygulama cevabı çözer,
  hatalı satırları gösterir, kontrol listesinde düzeltmeni ister ve onaylayınca programa kaydeder.
- **Derse özel ayarlar**: Ders adı ve kodu, dönemde kaç kez yapılacağı (otomatik ya da elle),
  devam şartı (varsayılan / yüzde / "kaç kez gelmeme hakkı"), ders başına yoklama sayısı.
- **Ana ekran**: her ders için devamsızlık sayısı, yüzdesi, kalan hak, girilen/kalan ders sayısı.
  Hakkı biten (veya sınırı aşılan) dersler kırmızı kartla en üstte gösterilir.
- **Eksik haftaları doldurma**: geçmişte işaretlenmemiş yoklamalar listelenir; elle *Girdim / Girmedim / Yapılmadı*
  seçilir. "Hepsini Girdim yap" ile hızlıca doldurup sadece girmediklerini değiştirebilirsin.
- **Ders detayı**: bir derse dokununca o dersin tüm haftaları; geçmişi düzeltebilir, ileri tarihli tatil/iptalleri
  işaretleyebilirsin.

## Hesaplama
- Toplam = dönemdeki ders sayısı × ders başına yoklama
  (otomatikte: 13 hafta × haftalık ders − "yapılmadı" işaretlenenler)
- Devam şartı %p ise devamsızlık hakkı = ⌊toplam × (100 − p) / 100⌋
  (ör. haftada 1 dersi olan ders, %70 şart: 13 × 0,3 → 3 hak; haftada 2 dersi olan: 26 → 7 hak)
- "Gelmeme hakkı" seçilirse hak doğrudan girilen sayıdır.

## Varsayılan program (OBS)
| Gün | Saat | Ders |
|---|---|---|
| Pazartesi | 10:30–13:29 | MAK 335 |
| Pazartesi | 15:30–17:29 | MAK 303 |
| Salı | 10:30–13:29 | MAK 309 |
| Salı | 14:30–17:29 | ITB 220 |
| Çarşamba | 08:30–10:29 | MAK 305 |
| Perşembe | 08:30–10:29 | MAK 303 |
| Perşembe | 10:30–12:29 | MAK 305 |
| Perşembe | 12:30–14:29 | MAK 317 |
| Perşembe | 15:30–17:29 | MAK 304E |
| Cuma | 10:30–12:29 | BEB 104 |
| Cuma | 14:00–16:59 | MAK 118 |

1. hafta = 28 Eylül 2026 (4 Kasım 2026 → 6. hafta). Ayarlardan değiştirilebilir.

## Derleme ve testler
```
./gradlew testDebugUnitTest   # hesaplama, içe aktarma, bildirim akışı ve arayüz testleri (Robolectric)
./gradlew assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`
Testler ekranların görüntülerini `app/build/screenshots/` altına da kaydeder.

## iPhone (iOS) sürümü

`ios/` klasöründe SwiftUI ile yazılmış iPhone sürümü var (iOS 16 ve üstü).

### iPhone'a kurulum
Apple, iPhone'a kurulan her uygulamanın imzalanmasını istiyor; bu yüzden Android'deki gibi dosyayı açıp kurmak yok.
Üç yol var:

1. **AltStore veya SideStore (Mac gerekmez, ücretsiz):** Releases → latest altından `Devamsizlik-imzasiz.ipa`
   dosyasını indir ve AltStore / SideStore ile kur. Uygulama kendi Apple kimliğinle imzalanır. Ücretsiz Apple
   hesabıyla imza 7 gün geçerli; AltStore / SideStore'dan haftada bir yenilemek gerekir.
2. **Mac + Xcode (ücretsiz Apple kimliği):**
   ```
   brew install xcodegen
   cd ios && xcodegen generate && open Devamsizlik.xcodeproj
   ```
   Xcode'da *Signing & Capabilities → Team* kısmından Apple kimliğini seç, iPhone'u bağla ve ▶︎ ile çalıştır.
   Ücretsiz hesapta da 7 gün geçerli.
3. **Apple Developer Program (yıllık ücretli):** TestFlight ile kendine ve arkadaşlarına dağıtılabilir.

### iOS'a özel farklar
- **Evet / Hayır / Ders yapılmadı** tuşlarını görmek için gelen bildirimi basılı tut ya da aşağı çek.
  Tuşa basınca cevap uygulama açılmadan kaydedilir.
- iOS'ta uygulamalar istedikleri an çalışamadığı için bütün sorular önceden zamanlanır ve iOS en fazla 64 bekleyen
  bildirime izin verir. Uygulama yaklaşık 2 haftalık soruyu sıraya koyar; sıra her açılışta ve her cevapta yenilenir.
  Uygulama uzun süre hiç açılmazsa "Bildirimler duracak" hatırlatması gelir.
- Veriler telefonda saklanır; Android ve iPhone arasında paylaşılmaz.

### Geliştirme
```
cd ios && swift test     # çekirdek testleri (hesaplamalar, içe aktarma, bildirim planı) — Linux'ta da çalışır
```
GitHub Actions'taki **iOS derle** iş akışı macOS'ta testleri çalıştırır, simülatörde ekran görüntüleri alır
(`ios-ekran-goruntuleri` çıktısı) ve imzasız `.ipa` dosyasını Releases'e ekler.
