# Origem e escopo

O SnapLivre implementa de forma independente os fluxos de busca, seleção de formatos e downloads de mídia. O trabalho original usou análise estática de um APK SnapTube como referência funcional; o aplicativo resultante tem código, ícones, nome e pacote próprios.

Esta publicação reúne código, testes, documentação e APKs do SnapLivre 1.0.1. Os materiais de análise do projeto de origem permanecem no servidor de trabalho. O SnapLivre não tem vínculo com os responsáveis pelo SnapTube.

## Cobertura

| Fluxo | Implementação |
| --- | --- |
| Busca | YouTube e SoundCloud |
| Entrada | URL, compartilhamento Android e navegador |
| Formatos e qualidades | Metadados retornados pelo yt-dlp |
| MP3 e streams separados | FFmpeg |
| Fila e arquivos | Serviço em primeiro plano, persistência e MediaStore |
| Biblioteca | Downloads, favoritos e histórico |

Não foram implementados feed do servidor original, contas, playlists em lote, letras, recursos de WhatsApp, limpeza ou plugins dinâmicos. A versão 1.0.1 não inclui player interno ou janela flutuante.

Os componentes abertos estão em [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md). Consulte [a situação dos testes](VALIDACAO.md).
