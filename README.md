# SnapLivre

Aplicativo Android em português para pesquisar mídia, analisar links e baixar vídeos ou áudio. Usa yt-dlp e FFmpeg, com interface própria e sem SDK de anúncios no aplicativo.

**Versão 1.0.1 · Android 8.0 ou superior · Pacote `org.snaplivre.app`**

[Baixar APKs](https://github.com/glokog/snaplivre/releases/latest) · [Instalação e uso](docs/INSTALACAO.md) · [Compilação](docs/COMPILACAO.md) · [Validação](docs/VALIDACAO.md)

## Download

| Arquivo | Compatibilidade | Tamanho aproximado |
| --- | --- | --- |
| [SnapLivre-arm64.apk](https://github.com/glokog/snaplivre/releases/download/v1.0.1/SnapLivre-arm64.apk) | ARM de 64 bits | 55 MiB |
| [SnapLivre-armv7.apk](https://github.com/glokog/snaplivre/releases/download/v1.0.1/SnapLivre-armv7.apk) | ARM de 32 bits | 48 MiB |
| [SnapLivre-universal.apk](https://github.com/glokog/snaplivre/releases/download/v1.0.1/SnapLivre-universal.apk) | ARM64, ARMv7, x86 e x86_64 | 193 MiB |

Se não souber a arquitetura do aparelho, use o APK universal. O tamanho inclui os mecanismos de extração e conversão. Os hashes estão em `SHA256SUMS` na release.

## Recursos

- Busca no YouTube e SoundCloud, entrada de URL e compartilhamento pelo Android.
- Escolha de MP4 e resolução, MP3 ou M4A conforme os formatos disponíveis.
- Conversão de áudio e combinação de streams com FFmpeg.
- Fila com pausa, retomada, cancelamento e notificações.
- Arquivos em **Downloads/SnapLivre**, abertos nos aplicativos do celular.
- Favoritos, histórico local, navegador e downloads somente por Wi-Fi.
- Atualização do extrator em Ajustes.

A versão 1.0.1 concentra-se em busca e downloads. Arquivos concluídos são abertos em um aplicativo de mídia instalado no aparelho.

## Começar

1. Baixe e instale o APK compatível.
2. Pesquise ou cole um link. Também é possível compartilhar um link de outro aplicativo com o SnapLivre.
3. Analise a mídia, escolha formato e qualidade e inicie o download.
4. Acompanhe a fila na aba Downloads; os arquivos ficam em **Downloads/SnapLivre**.

Veja [instalação, permissões e solução de problemas](docs/INSTALACAO.md).

## Compatibilidade e validação

O responsável pelo projeto confirmou o funcionamento em teste manual em **13/10/2026**. O aparelho e os cenários do teste não foram informados.

A compilação dos APKs e dos seis testes instrumentados foi concluída; o Android Lint registrou zero erros. Assinaturas e hashes dos três APKs foram conferidos para a publicação. A execução dos testes instrumentados em Android continua pendente. Consulte [o registro de validação](docs/VALIDACAO.md).

Sites podem exigir login, cookies ou mudar seus formatos. Esta versão não transfere cookies do navegador para o extrator nem oferece suporte a DRM. A busca tem duas fontes; outros sites são atendidos por link, conforme a cobertura do yt-dlp. Páginas externas podem apresentar publicidade própria.

## Desenvolvimento

Requer JDK 17, Android SDK Platform 35 e Build Tools 35.0.0. Usa Gradle 8.13 e Android Gradle Plugin 8.9.2.

```bash
git clone https://github.com/glokog/snaplivre.git
cd snaplivre
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew :app:assembleRelease
```

Defina `ANDROID_HOME` com o caminho do SDK. Os APKs ficam em `app/build/outputs/apk/release/`. A configuração atual usa assinatura de desenvolvimento; um build em outra máquina normalmente terá outro certificado. Veja [compilação e assinatura](docs/COMPILACAO.md).

## Documentação

- [Instalação, uso e solução de problemas](docs/INSTALACAO.md)
- [Compilação e assinatura](docs/COMPILACAO.md)
- [Arquitetura e dados locais](docs/ARQUITETURA.md)
- [Validação e limites dos testes](docs/VALIDACAO.md)
- [Origem e escopo](docs/ORIGEM.md)
- [Testes instrumentados](tests/README.md)
- [Histórico de versões](CHANGELOG.md)
- [Componentes e atribuições](THIRD_PARTY_NOTICES.md)

## Licença

Código próprio sob **GPL-3.0-or-later**. Consulte [LICENSE](LICENSE) e [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
