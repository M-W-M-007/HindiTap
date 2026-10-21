# HindiTap

Text select karo → popup menu mein **हिंदी** → translation. Bas.

Koi Accessibility Service nahi, koi screen-overlay permission nahi. Sirf Android ka
official `ACTION_PROCESS_TEXT` intent, jo Android 6.0 se har selection toolbar mein
third-party options allow karta hai.

## Translation quality — teen engines

App upar se neeche try karti hai, pehla jo chale wahi use hota hai:

1. **Gemini** (agar API key daali ho) — sabse behtar. Usko pata hota hai ki
   newspaper ka text hai, OCR ki chhoti galtiyan khud sudhaar leta hai, aur
   headline ko headline ki tarah translate karta hai.
2. **Public Google endpoint** — bina key ke. Achhi quality, lekin context nahi samajhta.
3. **ML Kit offline** — internet na ho tab.

### Gemini key

`aistudio.google.com/apikey` → Create API key. Credit card nahi chahiye.
App mein paste karke "Save key and test" dabayein.

Free tier par roughly **10 requests/min, 1500/day** (Google ab official per-model
table publish nahi karta; apna actual limit `aistudio.google.com/rate-limit` par
dikhta hai). Newspaper padhne ke liye yeh bahut zyada hai. Quota khatam hone par
app khud agla engine use kar legi.

Key sirf is phone ke SharedPreferences mein rehti hai. Free tier par bheja gaya
data Google product improvement ke liye use kar sakta hai — paid tier par nahi.

---

## Purani tulna — online vs offline

| | Online (default) | Offline |
|---|---|---|
| Model | Google ka bada model | ML Kit ~30 MB on-device |
| Quality | "गुस्साई भीड़ को तितर-बितर करने के लिए आंसू गैस छोड़ी गई" | "आंसू गैस को गुस्से में भीड़ फैलाने के लिए निकाल दिया" |
| Internet | Chahiye | Nahi chahiye |
| Privacy | Text Google ko jaata hai | Phone se bahar nahi jaata |

Default **online hai, offline fallback ke saath** — internet na ho toh apne aap
offline par chala jaata hai. Hamesha offline chahiye toh app mein switch ON kar dein.

Online endpoint Google ka public `translate_a/single` hai — official API nahi hai,
isliye kabhi band ho sakta hai. Us din app khud offline par fall back kar degi,
kuch toota hua nahi dikhega.

Headline text (`Tear Gas Fired To Disperse`) translate karne se pehle lowercase
kar diya jaata hai — title case models ki output kharab karta hai.

---

## Build karne ka tareeka

1. Android Studio kholein (Ladybug ya usse naya).
2. **File → Open** → ye `HindiTap` folder select karein.
3. Gradle sync khud ho jayega (pehli baar 5–10 min, dependencies download hoti hain).
4. OnePlus 15 ko USB se jodein. Phone par: Settings → About device → Version →
   "Build number" par 7 baar tap → wapas Settings → System → Developer options →
   **USB debugging** ON.
5. Android Studio mein upar device select karein → green **Run** button dabayein.

APK direct chahiye toh: **Build → Build Bundle(s)/APK(s) → Build APK(s)**.
File yahan milegi: `app/build/outputs/apk/debug/app-debug.apk`

---

## Pehli baar use karne se pehle

App kholein → **"Download Hindi model"** dabayein. ~30 MB, ek hi baar.
Iske baad internet ke bina bhi translation chalegi.

---

## Voice

Har translation panel par do buttons hain:

- **🔊 English** — jo shabd/line aapne box kiya tha, uska English pronunciation.
  "alleged", "tribunal" jaise shabdon ke liye yahi main kaam ka hai.
- **🔊 हिंदी** — Hindi translation sunne ke liye.

Speed thodi dheemi rakhi hai (0.88x) taaki single word saaf samajh aaye.

Hindi voice phone par na ho toh app bata degi. Download:
Settings → Language & input → Text-to-speech → engine ke gear icon → Install voice data → Hindi.

---

## E-paper mode (TOI / HT — image PDFs)

TOI aur HT ki e-paper **images** hoti hain, text layer nahi hota. Isliye wahan
selection menu, tile, clipboard — kuch kaam nahi aata. Us case ke liye screen
padhna padta hai (OCR).

**Setup (ek baar):**
1. App kholein → **Start screen reader**.
2. "Display over other apps" allow karein → wapas aakar button dobara dabayein.
3. Screen capture ka prompt aayega → **Start now**.

**Use:**
```
e-paper kholein → bubble tap → paragraph ke upar box banayein → Hindi neeche
```
E-paper band nahi hoti, zoom out nahi karna padta. Bubble ko kahin bhi drag kar sakte hain.
Band karne ke liye: notification mein "Band karein", ya app mein **Stop**.

**Trade-offs, pehle se jaan lein:**
- Screen capture chalu hone par status bar mein ek icon rehta hai. Android ka rule hai,
  hataya nahi ja sakta.
- Chhote newspaper print par OCR 100% nahi hoti — e-paper mein thoda zoom karke box
  banayein, accuracy saaf badh jaati hai.
- Phone restart ya "Stop" ke baad screen capture permission dobara deni padti hai.
  Android ka security rule hai.

---

## Use kaise karein

Do raaste hain, kyunki har app text selection ek tarah se handle nahi karta.

### Raasta 1 — selection menu (browser, notes, chat, email)

Text select karein → popup toolbar (Copy, Share wala) mein **हिंदी** → tap.
Seedha na dikhe toh ⋮ (three-dot) ke andar hoga — Android pehle 3-4 items hi
dikhata hai, baaki overflow mein chale jaate hain.

### Raasta 2 — Quick Settings tile (PDF readers)

Adobe Acrobat, Google Drive, Xodo jaise PDF readers **apna custom selection toolbar**
banate hain, kyunki PDF ka text normal Android TextView nahi hota. Isliye unme
PROCESS_TEXT options (matlab "हिंदी") kabhi nahi aayenge — ye reader badalne se
bhi theek nahi hoga. Unke liye:

```
select → Copy → shade neeche se kholein → "हिंदी" tile tap
```

Translation ek floating dialog mein aayegi, PDF peeche hi khuli rahegi.

**Tile ek baar add karni padegi:** notification shade poori neeche kholein →
pencil / edit icon → list mein "हिंदी" dhoondhein → drag karke active tiles mein laayein.

Backup: app kholne par bhi clipboard ka text khud translate ho jaata hai.

---

## Kya kaam NAHI karega

- **Images aur scanned PDFs.** Jahan text select hi nahi hota, wahan OCR chahiye —
  ye app woh nahi karti, aur na karne ka faisla jaanbujh kar hai (OCR ka matlab
  camera/screen capture permissions). Us ek case ke liye Google Lens hi behtar hai.
- Jo apps text selection hi disable kar dete hain (kuch DRM-protected readers).
- Firefox ka PDF viewer — Android par uska text layer selectable nahi hai.

---

## Customise karne ki jagah

| Kya badalna hai | Kahan |
|---|---|
| Menu ka naam ("हिंदी" ki jagah kuch aur) | `res/values/strings.xml` → `menu_label` |
| Target language (Hindi ki jagah Urdu/Marathi) | `TranslationEngine.kt` → `TranslateLanguage.HINDI` |
| Dialog ka size | `res/values/themes.xml` → `windowMinWidthMajor` |

---

## Agar "हिंदी" menu mein dikhe hi na

1. Confirm karein ki app install ho gayi hai (launcher mein icon dikhe).
2. Phone ek baar restart karein — Android apna intent cache refresh karta hai.
3. Kisi dusre app mein try karein (Chrome sabse reliable hai) — tab pata chalega
   ki problem app mein hai ya PDF reader mein.
