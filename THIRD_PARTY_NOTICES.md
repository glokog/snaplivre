# Componentes e atribuições

O código do SnapLivre é disponibilizado sob GPL-3.0-or-later, com o texto em `LICENSE`. Ele não inclui código, imagens, nome comercial ou servidores privados do SnapTube no aplicativo reconstruído. A análise do APK permanece em uma pasta separada.

| Componente | Versão | Fonte e licença |
| --- | --- | --- |
| youtubedl-android library/common/ffmpeg | 0.18.1 | https://github.com/yausername/youtubedl-android/tree/0.18.1 — GPL-3.0 |
| yt-dlp | 2026.08.19 | https://github.com/yt-dlp/yt-dlp/tree/2026.08.19 — Unlicense, ISC/MIT para componentes incorporados |
| FFmpeg e bibliotecas nativas | AAR ffmpeg 0.18.1 | Instruções/fontes do empacotamento: https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_FFMPEG.md e https://github.com/termux/termux-packages; licenças GPL/LGPL e dos componentes |
| Python e runtime incorporado | AAR library 0.18.1 | https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_PYTHON.md; PSF e licenças dos componentes |
| QuickJS | AAR library 0.18.1 | https://bellard.org/quickjs/ — MIT |
| AndroidX Core/AppCompat e demais módulos AndroidX | Dependências resolvidas pelo Gradle | https://android.googlesource.com/platform/frameworks/support/ — Apache-2.0 |
| Kotlin standard library | Dependência transitiva | https://github.com/JetBrains/kotlin — Apache-2.0 |
| Jackson | Dependência transitiva | https://github.com/FasterXML/jackson — Apache-2.0 |
| Apache Commons IO | Dependência transitiva | https://github.com/apache/commons-io — Apache-2.0 |

A release do yt-dlp contém [THIRD_PARTY_LICENSES.txt](https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/THIRD_PARTY_LICENSES.txt). Os AARs nativos mantêm seus arquivos internos de licenças no runtime empacotado. Consulte as fontes e receitas acima para reconstruir os componentes. As versões efetivas de todas as dependências são registradas em `dist/dependencies.txt`.

A fixture dos testes foi gerada com FFmpeg a partir de padrões `testsrc2` e `sine`, sem mídia de terceiros. Ícones vetoriais e interface são próprios.
