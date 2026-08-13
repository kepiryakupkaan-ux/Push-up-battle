# Push-Up Challenge

Rastgele bir arkadaşla eşleşip, karşılıklı canlı görüntülü, 90 saniyede kim daha
çok push-up yaparsa kazandığı bir uygulama. Kazanan +3 puan, kaybeden +1, berabere
+2 puan alır; skor tablosu bunun toplamına göre sıralanır.

## Nasıl çalışıyor (özet)
- **Video:** WebRTC ile telefonlar doğrudan birbirine bağlanır, görüntü Firebase'den geçmez.
- **Eşleştirme, süre senkronu, skor:** Firebase Firestore (bedava katman yeterli).
- **Push-up sayımı:** Kendi kameranın görüntüsü üstünde, cihazın kendi işlemcisinde
  Google ML Kit Pose Detection çalışır (omuz-dirsek-bilek açısını ölçer, kol ~155°'den
  ~90°'ye inip tekrar ~155°'ye çıkınca 1 tekrar sayılır). İnternete gitmez, cihazda çalışır.

## Kurulum

### 1. Firebase projesi (bedava)
1. https://console.firebase.google.com → yeni proje aç.
2. Android uygulaması ekle, package name: `com.example.pushup`
3. İndirdiğin `google-services.json`'ı `app/google-services.json` konumuna koy.
4. Firestore Database'i aç ("Create database", test modunda başlat).
5. Firestore → Rules sekmesine git, bu projedeki `firestore.rules` dosyasının içeriğini
   yapıştırıp yayınla. (Not: bu kurallar tamamen açık — herkes okuyup yazabilir.
   Küçük arkadaş grubu projesi için sorun değil, ama halka açarsan/büyürse
   birileri sahte skor yazabilir. O noktaya gelirsen kısıtlamamız gerekir.)

### 2. Android Studio
1. `PushUpApp` klasörünü Android Studio'da aç, Gradle senkronunu bekle
   (ML Kit + WebRTC + Firebase ilk seferde biraz büyük, sabırlı ol).
2. `app/google-services.json` yerinde değilse build hata verir.

### 3. Test
En az iki fiziksel Android telefon gerekiyor (kamera + gerçek ağ koşulları için
emülatör güvenilir değil).
1. Uygulamayı iki telefona da kur.
2. Her iki telefonda da isim gir → "Rastgele Rakip Bul".
3. İkisi de kuyrukta birbirini bulunca otomatik eşleşip görüntülü ekrana geçer.
4. Bağlantı kurulunca 3 saniye sonra 90 saniyelik geri sayım otomatik başlar.
5. Süre bitince kim kazandı ekranı çıkar, skor tablosuna otomatik kaydedilir.

## Bilinen sınırlar / dürüst notlar
- **Sayım tam hassas değil.** Işık, kamera açısı, kıyafet rengi gibi şeyler ML Kit'in
  omuz/dirsek/bilek noktalarını bulmasını etkiler. Kötü ışıkta sayım kaçırabilir.
  İyileştirme fikri: kamerayı yandan, tüm kolun göründüğü açıda tutmak en iyi sonucu verir.
- **"Honor system" senkron.** Süre başlangıcı ve tekrar sayıları Firestore üzerinden
  paylaşılıyor ama kimse doğrulamıyor — yani biri istese elle sahte sayı yazdırabilir
  (kod değiştirerek). Arkadaş grubunda güven meselesi, dert etme; gerçek "hile önleme"
  çok daha büyük bir iş olur.
- **Saat senkronu yaklaşık.** İki telefonun saati birebir aynı olmayabilir, birkaç
  saniyelik sapma normal.
- **Eşleştirme küçük ölçek için tasarlandı** (client-side matchmaking, Cloud Function yok).
  50-100 kişilik arkadaş grubunda sorunsuz çalışır.
- **Bu kod bu ortamda derlenip test edilmedi** (Android SDK burada yok). Android Studio'da
  ilk açtığında küçük hatalar (versiyon uyuşmazlığı vb.) çıkabilir — çıkarsa hata mesajını
  bana yapıştır, birlikte düzeltiriz.

## Sıradaki iyileştirme fikirleri (istersen ekleriz)
- Maç geçmişi (kiminle ne zaman oynadın)
- Rakip seçme (arkadaş kodu ile belirli birini davet etme, rastgele değil)
- Ses/mikrofon aç-kapa butonu
- Kalibrasyon ekranı ("birkaç push-up yap, eşiği sana göre ayarlayalım")
