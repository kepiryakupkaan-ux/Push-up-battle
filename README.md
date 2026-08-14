# Push-Up Challenge

Rastgele bir arkadaşla eşleşip, karşılıklı canlı görüntülü, 90 saniyede kim daha
çok push-up yaparsa kazandığı bir uygulama. Kazanan +3 puan, berabere +2, kaybeden
+0 puan alır (kaybetmek puan kazandırmaz); skor tablosu bu toplama göre sıralanır.

## v3'te eklenenler
- **Elo/Lig sistemi:** Galibiyet +30 Elo, berabere Elo değişmez, mağlubiyet bulunduğun
  lige göre değişen bir ceza (Bronz -10, Gümüş -15, Altın -22, Elmas -30). Lig, avatar
  çerçevesinin rengiyle her yerde görünüyor.
- **Maç geçmişi:** Kiminle, ne zaman, kaç-kaç oynadığını profilinden görebiliyorsun.
- **Rozetler:** 10 sabit rozet (ilk maç, 3'lük seri, tek maçta 30+ push-up, lig terfileri vb.),
  kazanınca maç sonu ekranında bildirim çıkıyor.
- **İstatistik ekranı:** Toplam maç, kazanma oranı, en iyi seri, galibiyet/mağlubiyet/beraberlik.
- **Haftalık skor tablosu:** Her Pazartesi 00:00'da otomatik sıfırlanan ayrı bir "Bu Hafta" tablosu
  (toplam puan tablosu kalıcı kalmaya devam ediyor).
- **Arkadaş sistemi:** Kullanıcı adıyla ekleme (karşılıklı, onay gerekmez), arkadaş listesi.
- **Maç sonu revanş / arkadaş daveti:** Bir arkadaşını doğrudan maça davet edebiliyorsun,
  o kabul edince direkt maç ekranına giriyorsunuz (kuyruğa girmeden).
- **Performans optimizasyonları:**
  - Tekrar sayıları artık Firestore yerine WebRTC DataChannel üzerinden (P2P) gidiyor -
    maç başına Firestore yazma sayısı ~20-40'tan ~2-5'e düştü.
  - ICE candidate'lar artık ayrı doküman değil, tek bir array alanında toplanıyor.
  - Eşleştirme kuyruk sorgusu küçültüldü (20 → 8 kayıt) ve 60 saniyede otomatik "kimse yok" mesajı.
  - TURN sunucusu ExpressTURN'e taşındı (ücretsiz plan, ayda 1000 GB - bkz. aşağıdaki kurulum notu).
- **Teknik sağlamlaştırma:**
  - Maç ekranında ekran artık kararmıyor (`FLAG_KEEP_SCREEN_ON`).
  - Bağlantı 10 saniyeden uzun kopuk kalırsa maç güvenli şekilde sonlandırılıyor (puan/Elo işlenmez).
  - İzin reddedilirse doğrudan uygulama ayarlarına yönlendiren buton eklendi.

## v2'de değişenler
- **Hesap sistemi:** Kayıt ol / giriş yap, profil fotoğrafı. Aynı kullanıcı adıyla
  aynı anda sadece bir cihaz açık kalabilir - başka bir cihazdan giriş yapılırsa
  eski cihaz otomatik olarak dışarı atılır ("Hesabına başka bir cihazdan giriş yapıldı").
- **Karşı kamera görünmüyordu bug'ı düzeltildi:** WebRTC `UNIFIED_PLAN` modunda
  çalışıyordu ama kod eski `onAddStream` callback'ini dinliyordu (Unified Plan'da
  güvenilir tetiklenmiyor) - bu yüzden "Bağlandı" yazsa da karşı görüntü hiç gelmiyordu.
  Artık doğru callback (`onAddTrack`) kullanılıyor.
- **Push-up sayımı daha sıkı:** Artık sadece dirsek açısına değil, (1) gövdenin kameraya
  yatay/plank pozisyonunda olmasına ve (2) omzun gerçekten inip çıkmasına da bakıyor.
  Kolunu havada kaldırıp indirmek artık saymıyor.
- **İskelet overlay:** Kendi kameranın üstünde omuz/dirsek/bilek/kalça/diz/ayak bileği
  noktaları ve aralarındaki çizgiler çiziliyor; duruşun uygun değilse çizgiler kırmızı olur.
- **Kazanma/kaybetme animasyonu:** Kazanınca konfeti patlaması, kaybedince ekran sallanması.

## Nasıl çalışıyor (özet)
- **Video:** WebRTC ile telefonlar doğrudan birbirine bağlanır, görüntü Firebase'den geçmez.
- **Hesaplar, eşleştirme, süre senkronu, skor:** Firebase Firestore (bedava katman yeterli).
- **Push-up sayımı:** Kendi kameranın görüntüsü üstünde, cihazın kendi işlemcisinde
  Google ML Kit Pose Detection çalışır (omuz-dirsek-bilek açısını ölçer + gövde/omuz
  kontrolleri, bkz. yukarısı). İnternete gitmez, cihazda çalışır.

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
2. Her iki telefonda da bir hesap oluştur (kullanıcı adı + şifre, istersen profil fotoğrafı).
3. Giriş yap → "Rastgele Rakip Bul".
4. İkisi de kuyrukta birbirini bulunca otomatik eşleşip görüntülü ekrana geçer.
5. Bağlantı kurulunca 3 saniye sonra 90 saniyelik geri sayım otomatik başlar.
6. Süre bitince kim kazandı ekranı çıkar, skor tablosuna otomatik kaydedilir.

### 4. Push-up sayımı için en iyi kamera açısı
Telefonu yan koy, tüm kolun ve gövdenin kadraja girdiği bir açıda dur (klasik "yandan
plank" görünümü). Sistem artık gövdenin yatay olup olmadığını da kontrol ettiği için
telefon çok dik/yandan değilse ya da sadece üst gövde görünüyorsa sayım tutmayabilir.

## Bilinen sınırlar / dürüst notlar
- **Sayım hâlâ %100 hassas değil.** Işık, kamera açısı, kıyafet rengi gibi şeyler ML Kit'in
  noktaları bulmasını etkiler. Gövde/omuz kontrolleri sahte tekrarları büyük ölçüde
  eliyor ama eşik değerleri (RepCounter.kt içindeki `minShoulderDrop`, `maxTorsoTiltDeg`)
  senin kamera açına göre ince ayar isteyebilir.
- **İskelet overlay'in hizası cihazda test edilmedi.** Bu ortamda Android SDK/gerçek
  cihaz olmadığı için PoseAnalyzer'daki koordinat dönüşümü (rotation + mirror) mantıken
  doğru ama ilk çalıştırmada noktalar hafif kaymış görünebilir - PoseAnalyzer.kt'deki
  `rotatedMirroredNormalized` fonksiyonunun içindeki yorumda ne değiştireceğin yazıyor.
- **"Honor system" senkron.** Süre başlangıcı ve tekrar sayıları Firestore üzerinden
  paylaşılıyor ama kimse doğrulamıyor — yani biri istese elle sahte sayı yazdırabilir
  (kod değiştirerek). Arkadaş grubunda güven meselesi, dert etme; gerçek "hile önleme"
  çok daha büyük bir iş olur.
- **Hesap sistemi basit tutuldu.** Firebase Auth yerine kendi Firestore tabanlı
  kullanıcı/şifre sistemimiz var (bkz. AuthClient.kt). Şifreler hash'lenip saklanıyor
  ama firestore.rules tamamen açık olduğu için teorik olarak herkes hash+salt'ı
  okuyabilir. Küçük arkadaş grubu için sorun değil, halka açarsan Firebase Auth'a geçmek gerekir.
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
- Şifre unuttum akışı
