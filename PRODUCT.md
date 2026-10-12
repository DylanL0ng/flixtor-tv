# Personal TV client

Platform: Android TV / Google TV. Kotlin, Compose for TV, Media3.

Personal sideloaded client for ONN Android/Google TV hardware and Chromecast with Google TV. Sky IP061 has no supported APK installation path. Users browse Flixtor, sign into their existing VIP account, and watch using a remote. Website access rules remain authoritative. No WebView.

One shared service login per device with up to seven household profiles. Each profile has its own local watchlist, viewing progress, avatar, preferred maximum video quality, autoplay preference, and optional four digit PIN asked for when the profile is chosen. Cold launch opens the profile chooser. Cross-device Sync Codes are outside this release. No subscription purchasing or password management in the APK.

Personal source and signed release distribution: DylanL0ng/flixtor-tv on GitHub. Updates download automatically and install in place through Android's installer. Structural-error reports are user-triggered and delivered through a dedicated Vercel/Resend relay; unavailable deployment configuration must stay visible.
