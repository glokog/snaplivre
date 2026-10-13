# Instalação e uso

## Instalar

1. Abra a [release mais recente](https://github.com/glokog/snaplivre/releases/latest).
2. Baixe ARM64 para ARM de 64 bits, ARMv7 para ARM de 32 bits ou universal se não souber a arquitetura.
3. Abra o APK. Se o Android solicitar, habilite a instalação de aplicativos para o navegador ou gerenciador de arquivos usado nessa etapa.
4. Conclua a instalação e abra o SnapLivre.

Requer Android 8.0/API 26 ou superior. O pacote `org.snaplivre.app` instala ao lado do SnapTube.

## Pesquisar e baixar

Escolha YouTube ou SoundCloud e pesquise. Também é possível colar um link ou usar **Compartilhar → SnapLivre** em outro aplicativo. Após a análise, escolha MP4 e resolução, MP3 ou M4A. A lista depende do conteúdo retornado pelo extrator.

A aba Downloads mostra a fila e permite pausar, retomar e cancelar. Arquivos concluídos aparecem em **Downloads/SnapLivre** e abrem com um aplicativo instalado no celular.

Favoritos e histórico ficam no aparelho. Em Ajustes, é possível restringir downloads ao Wi-Fi e atualizar o extrator. O navegador oferece atalhos e detecção de mídia.

## Permissões

| Permissão | Finalidade |
| --- | --- |
| Internet e estado da rede | Busca, análise de links e transferências |
| Notificações | Andamento dos downloads, conforme a versão do Android |
| Serviço em primeiro plano e wake lock | Manter a transferência ativa |
| Escrita no armazenamento até Android 9/API 28 | Salvar arquivos no Android antigo |

Nas versões recentes, a exportação usa MediaStore. Consulte o [manifesto](../app/src/main/AndroidManifest.xml).

## Conferir integridade

Baixe o APK e `SHA256SUMS` da mesma release. No Linux:

```bash
sha256sum SnapLivre-arm64.apk
```

Compare a saída com a linha correspondente de `SHA256SUMS`. Se tiver todos os arquivos listados na mesma pasta, use `sha256sum -c SHA256SUMS`.

No PowerShell:

```powershell
Get-FileHash .\SnapLivre-arm64.apk -Algorithm SHA256
```

## Solução de problemas

| Situação | O que verificar |
| --- | --- |
| APK incompatível | Android 8.0 ou superior e arquitetura correta; tente o universal |
| Conflito ao atualizar | Um build de outra máquina pode ter certificado diferente |
| Site exige login ou recusa análise | Atualize o extrator; cookies do navegador não são transferidos ao yt-dlp |
| Tarefa aguarda rede | Conexão e preferência de baixar somente por Wi-Fi |
| Download pausou após encerrar o processo | Retome manualmente na aba Downloads |
| Arquivo não abre | Escolha um aplicativo compatível com o formato |

Relate problemas em [Issues](https://github.com/glokog/snaplivre/issues) com versão do Android e do app, arquitetura do APK, erro e passos para reproduzir. Evite incluir senhas, cookies ou links privados.
