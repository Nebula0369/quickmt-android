# QuickMT Oyun Altyazı (Android APK — %100 Çevrimdışı)

Sadece **`quickmt`** (`CTranslate2 INT8` + `SentencePiece`, ARM64 NDK) ve çevrimdışı **Google ML Kit OCR** kullanarak Android oyunlarının üzerine anlık Türkçe altyazı getiren uygulama.

## PC'ye Android SDK Kurmadan 5 Dakikada Hazır `.apk` Alma (GitHub Actions)
1. GitHub'da yeni (boş) bir depo oluşturun (ör. `quickmt-android-subtitles`).
2. Bu klasördeki tüm dosyaları o depoya yükleyin (veya `git push` yapın):
   ```powershell
   git init
   git add .
   git commit -m "Initial QuickMT Android app"
   git branch -M main
   git remote add origin https://github.com/KULLANICI_ADIN/quickmt-android-subtitles.git
   git push -u origin main
   ```
3. GitHub deponuzda üst menüden **Actions** sekmesine tıklayın → **"Build QuickMT Android APK"** iş akışı otomatik çalışacaktır (ilk seferde CTranslate2 + SentencePiece ARM64 derlemesi dahil ~8–12 dakika sürer; sonraki derlemeler önbellekten ~2 dakikada biter).
4. İşlem yeşil tik (✓) olunca en alttaki **Artifacts** bölümünden **`QuickMT-Game-Subtitles-arm64-APK`** dosyasını indirip telefonunuza kurun.

## Telefonda Kullanım
1. Uygulamayı açıp **"Modeli İndir (~199 MB)"** butonuna basın (`value-productions/quickmt-en-tr-int8` ilerleme çubuğuyla ve kaldığı yerden devam destekli olarak iner — bir kez inince tamamen internetsiz çalışır).
2. **"Test Et"** ile çeviri hızını (telefonunuzda genelde ~30–70 ms) hemen doğrulayın.
3. **Altyazı Kutu Konumu**'nu seçin:
   - **Hemen Üstünde (Önerilen):** Sıfır titreme, kesintisiz okuma.
   - **Üstünü Kapatma Modu:** Orijinal İngilizce altyazının tam üstünü koyu kutuyla kapatır.
4. **"Oyun Üstünde Başlat"** deyin, *Üstte Göster* ve *Ekran Yakalama* izinlerini onaylayın.
5. Oyununuzu açın, kenardaki küçük yüzen çubuktan **`⌖`** tuşuna basıp parmağınızla İngilizce altyazı bölgesini seçin — bitti!
