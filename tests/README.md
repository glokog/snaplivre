# Testes instrumentados

Os testes de `app/src/androidTest` usam o mecanismo real embarcado (Python,
yt-dlp e FFmpeg), o serviço Android e o MediaStore. Um `ServerSocket` escuta
somente `127.0.0.1` no próprio aparelho e fornece uma mídia sintética; não há
dependência de YouTube, contas ou URLs externas.

Executar em instalação de teste ociosa, num aparelho/emulador Android 10 ou
posterior, com rede Android ativa. O acesso real aos dados do servidor ocorre
localmente, mas o serviço verifica a existência da rede antes de iniciar.
Os testes recusam começar se um serviço de download já estiver ativo. Cada
teste restaura o histórico preexistente e a preferência de Wi-Fi e apaga
apenas os downloads, entradas MediaStore e temporários que criou.

```bash
./gradlew :app:connectedDebugAndroidTest
```

Para executar classes individualmente:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.snaplivre.app.DownloadIntegrationTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.snaplivre.app.UiNavigationTest
```

Cobertura: inspeção e exportação MP4 com H.264/AAC; extração MP3 com FFmpeg;
URI pública, MIME, publicação sem `IS_PENDING`, duração e amostras reais de
codec; pausa com `.part`, retomada HTTP com `Range` não zero; itens pausados
e cancelados na fila; cancelamento do item ativo sem impedir o seguinte;
navegação das cinco telas e leitura de links compartilhados. A suíte contém
seis testes; a reprodução interna foi removida do aplicativo.

A transferência lenta acrescenta um box ISO-BMFF `free` de 1 MiB ao MP4.
Isso mantém uma estrutura válida e torna pausa/retomada determinísticas.
Os testes não cobrem sites públicos que exigem autenticação ou alteram seus
extratores e não executam nem modificam o APK original.

A fixture `fixture-av.mp4` tem dois segundos de imagem sintética e tom de
440 Hz, sem material de terceiros. Pode ser regenerada com:

```bash
ffmpeg -hide_banner -loglevel error \
  -f lavfi -i testsrc2=size=320x180:rate=15 \
  -f lavfi -i sine=frequency=440:sample_rate=44100 \
  -t 2 -c:v libx264 -preset ultrafast -pix_fmt yuv420p \
  -c:a aac -b:a 96k -movflags +faststart \
  -y app/src/androidTest/assets/fixture-av.mp4
```
