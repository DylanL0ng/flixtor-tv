# FlixTor TV

Platform: Android TV / Google TV. Kotlin host activity, SolidTV TypeScript application in a WebView, and native Android Media3 playback.

Personal sideloaded TV client for ONN Android/Google TV hardware and Chromecast with Google TV. Users browse Flixtor, sign in to an existing VIP account, and watch using a remote. Website access rules remain authoritative. The WebView renders the catalogue, profiles, forms and playback controls; native Media3 owns video playback and captions. Browser fixtures support development without claiming live account or device acceptance.

The experience is remote-first and supports keyboard and pointer input during browser development. Cold launch opens the profile chooser. Up to seven household profiles share one device service login, with separate local watchlists, viewing progress, avatars, quality preference, autoplay setting, and optional four-digit PIN. Cross-device sync, subscription purchasing and password management are outside this client.

Anime has a dedicated catalogue and search through the built-in HiAnime adapter. Manual starts and resumes offer English dub or Japanese with subtitles; autoplay retains the selected version and stops if it is unavailable. Anime uses native playback and the shared profile library, with version-specific viewing progress and local skip marks. AniSkip adds community timestamps through explicit MAL/episode mappings. Missing regions can be edited on the paused timeline, including reusable custom types. Skip actions and buffer preferences are per profile.

The visual system is Liquid Cinema: full-bleed artwork stages, floating translucent navigation and actions, dense task surfaces, bright-art scrims, and distinct focus/selection/pending states. It uses bundled Roboto and the SolidTV canvas renderer on a 1920 × 1080 logical scene. Layouts scale to TV output and respect adjustable screen-fit margins. Reduced motion and reduced-transparency variants are supported.

Personal source and signed release distribution: DylanL0ng/flixtor-tv on GitHub. Updates download automatically and install in place through Android's installer. Structural-error reports are user-triggered and delivered through a dedicated Vercel/Resend relay; unavailable deployment configuration must stay visible.
