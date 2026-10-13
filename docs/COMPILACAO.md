# Compilação e assinatura

## Requisitos

- JDK 17.
- Android SDK Platform 35 e Build Tools 35.0.0.
- Acesso a Google Maven e Maven Central.

O wrapper fixa Gradle 8.13 com checksum da distribuição. O projeto usa Android Gradle Plugin 8.9.2, SDK mínimo 26 e alvo 35.

## Compilar

```bash
git clone https://github.com/glokog/snaplivre.git
cd snaplivre
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew :app:assembleRelease
```

Defina `ANDROID_HOME` com o SDK instalado. Ou crie `local.properties` manualmente:

```properties
sdk.dir=/caminho/para/android-sdk
```

No Windows, use `gradlew.bat :app:assembleRelease` e configure o SDK em `local.properties`.

Os APKs ficam em `app/build/outputs/apk/release/`. Há splits ARM64, ARMv7, x86 e x86_64, além do universal.

## Extrator e dependências

`app/src/main/res/raw/ytdlp` fixa **yt-dlp 2026.08.19**. SHA-256:

```text
1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6
```

Dependências principais: youtubedl-android library/ffmpeg 0.18.1, AndroidX Core 1.15.0 e Annotation 1.9.1. Veja [a lista resolvida](../dist/dependencies.txt) e [as atribuições](../THIRD_PARTY_NOTICES.md).

## Assinatura

`app/build.gradle` usa `signingConfig signingConfigs.debug` no build release. Os APKs 1.0.1 mantêm o certificado de desenvolvimento existente; a chave privada não integra o repositório ou a release.

SHA-256 do certificado dos APKs publicados:

```text
b644e8845dba7dd24053a0e3039472391ea7544cf7cf2ac92940d8f56768f9dc
```

Um build em outra máquina normalmente terá certificado diferente. Para distribuir sua própria linha de atualizações, configure e preserve uma chave release. Mudar o certificado impede atualizar diretamente uma instalação assinada com o anterior. Guarde chave e credenciais fora do Git.

## Verificar

```bash
./gradlew :app:lintDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
```

O último comando exige um Android funcional. A suíte tem seis testes, com mídia sintética e servidor HTTP local. Veja [testes](../tests/README.md) e [validação](VALIDACAO.md).
