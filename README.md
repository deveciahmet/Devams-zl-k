# Devamsızlık Takibi (Android)

İTÜ ders programına göre her dersin bitiminde **"MAK 335 dersine girdin mi?"** diye bildirim gönderen,
devamsızlıklarını sayan ve hakkı biten dersleri uyaran Android uygulaması.

## Kurulum
1. Repodaki **Releases → latest** altından `Devamsizlik.apk` dosyasını telefona indir
   (ya da Actions sekmesindeki son çalışmanın `Devamsizlik-apk` çıktısını).
2. Dosyayı aç, "bilinmeyen kaynaklardan yüklemeye" izin ver ve yükle.
3. Uygulamayı bir kez aç ve **bildirim iznini ver**.

## Özellikler
- **Ders bitiminde bildirim**: *Evet / Hayır / Ders yapılmadı* tuşları. Tuşa basınca uygulamayı açmadan kaydedilir.
- **Ana ekran**: her ders için devamsızlık sayısı, yüzdesi, kalan hak, girilen/kalan ders sayısı.
- **Özel uyarı**: hakkı biten (veya sınırı aşılan) dersler kırmızı kartla en üstte gösterilir.
- **Eksik haftaları doldurma**: geçmişte işaretlenmemiş dersler listelenir; elle *Girdim / Girmedim / Ders yapılmadı* seçilir.
  "Hepsini Girdim yap" ile hızlıca doldurup sadece girmediklerini değiştirebilirsin.
- **Ders detayı**: bir derse dokununca o dersin tüm haftaları; geçmişi düzeltebilir, ileri tarihli tatil/iptalleri işaretleyebilirsin.
- **Ayarlar**: dönem başlangıcı / şu anki hafta, toplam hafta (13), devamsızlık sınırı (%30), ders programı ekle/sil, test bildirimi.

## Hesaplama
- Toplam ders = 13 hafta × haftalık oturum − "ders yapılmadı" olanlar
- Devamsızlık hakkı = ⌊toplam × %30⌋  (ör. haftada 1 oturumlu ders: 13 × 0,3 = 3 hak)
- Haftada 2 oturumu olan dersler (MAK 303, MAK 305) her oturum ayrı sayılır (26 oturum → 7 hak).

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

## Derleme
```
./gradlew assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`
