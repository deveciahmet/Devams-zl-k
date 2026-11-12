# Devamsızlık Takibi (Android)

İTÜ ders programına göre her dersin bitiminde **"Isı Geçişi (MAK 303) dersine girdin mi?"** diye bildirim gönderen,
devamsızlıklarını sayan, hakkı biten dersleri uyaran ve sınavlarına kaç gün kaldığını gösteren Android uygulaması.

## Kurulum
1. Repodaki **Releases → latest** altından `Devamsizlik.apk` dosyasını telefona indir
   (ya da Actions sekmesindeki son çalışmanın `Devamsizlik-apk` çıktısını).
2. Dosyayı aç, "bilinmeyen kaynaklardan yüklemeye" izin ver ve yükle. Eski sürümün üzerine kurulur, kayıtlar korunur.
3. Uygulamayı bir kez aç ve **bildirim iznini ver**.
4. İstersen ana ekrana **Devamsızlık widget'ını** ekle (ana ekrana uzun bas → Widget'lar → Devamsızlık).

## Uygulama
Altta üç sekme var:
- **Dersler**: "Bugün" kartında bugünkü dersler ve durumları (Girdin / Girmedin / İşaretsiz / Sıradaki), sıradaki
  sınav, işaretlenmemiş yoklamalar, hakkı biten ya da azalan dersler ve her dersin kalan devamsızlık hakkı.
  Bir derse dokununca o dersin bütün haftaları açılır; ekran bu haftaya kaydırılmış olarak gelir, kalan hak üstte durur.
- **Sınavlar**: vize, final, quiz tarihlerini elle eklersin; her sınava kaç gün kaldığı görünür, geçmiş sınavlar
  altta soluk durur. Sınavdan önceki akşam 20:00'de hatırlatma gelir (Ayarlar'dan kapatılabilir).
- **Ayarlar**: dönem, tatil günleri, yeni dönem, dersler, bildirimler ve yedekleme.

## Özellikler
- **Programı Kepler'den ekleme**: Kepler → Ders Programı ekranının ekran görüntüsünü seç (birden fazla olabilir).
  Fotoğraftaki ders kodları ve CRN'ler telefonda (internetsiz, Google ML Kit ile) okunur. Ders **adları, günleri,
  saatleri ve derslikleri** İTÜ'nün [açılan dersler](https://obs.itu.edu.tr/public/DersProgram) sayfasından bulunur:
  - Fotoğrafta CRN varsa şube kesin bulunur.
  - Yoksa haftalık tablodaki gün sütunundan ve saatten (ya da listedeki gün/saatten) şube seçilir.
  - Tek şubesi olan (ya da şubeleri aynı saatte olan) derslerde soru sorulmaz; anlaşılamazsa "Hangi şubedesin?" diye sorulur.
  - Fotoğraf yerine ders kodlarını / CRN'leri yazabilir ya da Kepler sayfasındaki metni kopyalayıp yapıştırabilirsin.
  - Aynı ders, gün ve saatteki derslerin eski yoklama geçmişi korunur.
- **Ders adlarını İTÜ'den doldurma**: adı boş dersler açılışta (günde en fazla bir kez) İTÜ'den doldurulur; Ayarlar →
  Dersler'deki düğmeyle elle de yapılabilir. Elle yazdığın adlar değiştirilmez. Şubesi belliyse derslik de eklenir.
- **Yapay zekâ ile ekleme** (hazır metni kopyala + fotoğrafı yapay zekâya gönder + cevabı yapıştır) şimdilik kapalı
  (`PROMPT_IMPORT_ENABLED`); kodu duruyor.
- **Ders bitiminde bildirim**: *Evet / Hayır / Ders yapılmadı* tuşları; tuşa basınca uygulamayı açmadan kaydedilir.
  Bildirimin kendisine dokununca **o dersin işaretleme ekranı** o derse kaydırılmış olarak açılır.
- **Derste birden fazla yoklama**: Ders başına yoklama sayısı (1–4). 2 yoklamalı derste ilk soru dersin başlamasından
  1 saat sonra, ikincisi ders bitiminde gelir; her saat ayrı sayılır.
- **Hatırlatma**: Cevap vermez ya da bildirimi kapatırsan 15 / 30 / 60 dk sonra bir kez daha sorar.
- **Haftalık özet**: Seçtiğin gün ve saatte son 7 günün özeti, riskli dersler ve önümüzdeki 7 günün sınavları.
- **Erken uyarı**: "Hayır" deyince kalan hakkın eşiğe inerse hemen uyarır; hakkı azalan derslerden 30 dk / 1 saat /
  2 saat önce "kaçırma" hatırlatması gönderir.
- **Tatil günleri**: Ayarlar → Tatil günleri'nden bir gün (ya da gün aralığı) seç; o günlerin **bütün dersleri
  "yapılmadı"** sayılır, soru gelmez ve devamsızlık hakları yeniden hesaplanır. Tatili kaldırınca geri alınır.
- **Yeni dönem başlat**: Dönem bitince tek düğmeyle bu dönemin programı, yoklamaları, tatilleri ve sınavları arşivlenir;
  yeni dönem boş programla başlar (bildirim ayarları ve devam şartı korunur). Hemen ardından "Geri al" ile vazgeçilebilir.
  Ayarlar → **Geçmiş dönemler**'de her dönemin özeti (kaç derse girdin / girmedin, derslerin son durumu, sınavlar)
  görülür; istenirse arşivdeki dönem geri yüklenir.
- **Yedekleme ve aktarma**: Ayarlar → Yedekleme'den bütün kayıtlar (program, yoklamalar, sınavlar, tatiller, geçmiş
  dönemler, ayarlar) tek bir `.json` dosyasına kaydedilir (Drive'a, indirilenlere vb.). Yeni telefonda "Yedekten geri
  yükle" ile aynen geri gelir.
- **Ana ekran widget'ı**: Şu an süren / sıradaki ders (saat ve derslik), sıradaki sınava kalan gün ve en önemli iki
  uyarı (hakkı biten ders, son hak, işaretlenmemiş yoklamalar). Küçük boyutta sınav ve sıradaki ders görünür.
  Dokunulan yere göre ilgili ekran açılır; dersler başlayıp bittikçe ve gece yarısı kendiliğinden yenilenir.
  Android 12+'da duvar kâğıdı renklerine uyar, koyu temayı destekler.
- **Derse özel ayarlar**: Ders adı ve kodu, ders saatleri ve derslikleri, dönemde kaç kez yapılacağı (otomatik ya da
  elle), devam şartı (varsayılan / yüzde / "kaç kez gelmeme hakkı"), ders başına yoklama sayısı, dersi programdan kaldırma.
- **Eksik haftaları doldurma**: geçmişte işaretlenmemiş yoklamalar listelenir; "Hepsini Girdim yap" ile hızlıca
  doldurup sadece girmediklerini değiştirebilirsin.
- **Test bildirimi**: Ayarlar'dan bildirimlerin geldiğini denemek için; teste verilen cevap **kaydedilmez**.

## Hesaplama
- Toplam = dönemdeki ders sayısı × ders başına yoklama
  (otomatikte: 13 hafta × haftalık ders − "yapılmadı" işaretlenenler, tatiller dahil)
- Devam şartı %p ise devamsızlık hakkı = ⌊toplam × (100 − p) / 100⌋
  (ör. haftada 1 dersi olan ders, %70 şart: 13 × 0,3 → 3 hak; haftada 2 dersi olan: 26 → 7 hak)
- "Gelmeme hakkı" seçilirse hak doğrudan girilen sayıdır.

## Varsayılan program (2026-2027 Güz, OBS)
İlk kurulumda kayıtlı program yoksa bu program kullanılır; ders adları ve derslikler İTÜ'den doldurulur.

| Gün | Saat | Ders |
|---|---|---|
| Pazartesi | 10:30–13:29 | MAK 335 Akışkanlar Mekaniği II |
| Pazartesi | 15:30–17:29 | MAK 303 Isı Geçişi |
| Salı | 10:30–13:29 | MAK 309 Mekanik Titreşimler |
| Salı | 14:30–17:29 | ITB 220 Psikoloji |
| Çarşamba | 08:30–10:29 | MAK 305 Sistem Dinamiği ve Kontrol |
| Perşembe | 08:30–10:29 | MAK 303 Isı Geçişi |
| Perşembe | 10:30–12:29 | MAK 305 Sistem Dinamiği ve Kontrol |
| Perşembe | 12:30–14:29 | MAK 317 Makine Mühendisleri İçin Mühendislik Ekonomisi |
| Perşembe | 15:30–17:29 | MAK 304E Meaurement Laboratory (İTÜ'deki yazımıyla) |
| Cuma | 10:30–12:29 | BEB 104 Tenis Teknik ve Taktik Eğitim |
| Cuma | 14:00–16:59 | MAK 118 Statik |

1. hafta = 28 Eylül 2026 (4 Kasım 2026 → 6. hafta). Ayarlardan değiştirilebilir.

## Derleme ve testler
```
./gradlew testDebugUnitTest   # hesaplama, İTÜ/Kepler çözümleme, bildirim, widget, yedekleme ve arayüz testleri (Robolectric)
./gradlew assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`

Testler ekranların ve widget'ın görüntülerini `app/build/screenshots/` altına da kaydeder. İTÜ testleri ağa çıkmaz;
`app/src/test/resources/itu/` altındaki, gerçek açılan dersler sayfasından alınmış (öğretim üyesi adları değiştirilmiş)
örnek sayfaları kullanır.
