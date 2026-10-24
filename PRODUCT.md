# Product

## Platform

web

(O registro aceita só web, ios, android ou adaptive. O Apex é um **app nativo de Windows** feito em Kotlin com Compose Multiplatform e
desenhado com Skia, não um site: mouse, teclado e janela de PC. "web" é o mais próximo em linguagem de interface: tela de computador,
cursor e atalhos. O código compartilhado está organizado para um Android no futuro; isso ainda não existe.)

## Users

Qualquer pessoa que acompanha vídeos e lives no YouTube, na Twitch e na Kick e quer tudo num lugar só (confirmado pelo autor: o público
é geral, não só o próprio autor). Hoje quem mais usa é o público brasileiro, porque a interface só existe em português (Brasil).
Situações reais já vistas no projeto: assistir uma live num monitor enquanto joga em outro, deixar música tocando enquanto vê outro vídeo,
acompanhar várias lives ao mesmo tempo (Multi) e maratonar vídeos longos retomando de onde parou.

## Product Purpose

Assistir vídeos e lives das três plataformas numa janela só: início e inscrições misturando YouTube, Twitch e Kick, lives com chat
(com emotes da plataforma e das extensões 7TV, BetterTTV e FrankerFaceZ), Multi para várias lives, abas de vídeo, música em segundo
plano, histórico, playlists e assistir depois, sem Shorts e sem downloads. Sucesso é a pessoa trocar os três sites pelo Apex no dia a dia.

## Positioning

Um único app de computador para as três plataformas, com a lista de canais seguidos unificada e sincronizada com as contas, um player
próprio (libVLC) com recursos que os sites não têm juntos: volume até 200%, "voltar ao vivo" que tira o atraso, Multi misturando
plataformas, abas, música de fundo e atalhos de teclado em tudo.

## Operating Context

- Janela de PC, quase sempre maximizada; muitas vezes em segundo monitor ao lado de um jogo (o app economiza quando está escondido).
- Uso diário e longo: abrir, ver o que está ao vivo, continuar vídeos, alternar entre lives.
- Contas conectadas lendo o login do navegador (Firefox ou Chromium) e conta própria do Apex para sincronizar entre aparelhos.
- Distribuição pelo GitHub (instalador, portátil, atualização automática assinada).

## Capabilities and Constraints

- Funções existentes estão no README ("O que tem"); nenhuma pode sumir num redesenho.
- Interface só em português (Brasil) hoje; o público pretendido é geral, então outros idiomas são um objetivo ainda não feito.
- O player é libVLC desenhado dentro do Compose; a interface é Compose Material 3 com tema próprio (`theme/ApexTheme.kt`).
- Desempenho importa: o app roda ao lado de jogos.
- Indefinido: um novo nome ou logotipo (o autor não marcou o nome e o logo atuais como intocáveis).

## Brand Commitments

Confirmados pelo autor como algo que precisa continuar:

- Os temas **Subaru** (azul e dourado, preto e dourado) e a animação do carro de rali ao trocar para eles.
- O **jeito YouTube**: barra lateral, busca no topo e grade de miniaturas.
- Os **atalhos de teclado** e o **comportamento do player**.
- Licença GPL-3.0.

## Evidence on Hand

- README.md (funções e decisões técnicas), docs/ (contas e sincronização, publicação).
- Nenhum depoimento, número de usuários, avaliação ou imprensa: não inventar.

## Product Principles

1. As três plataformas como uma só: o que a pessoa segue importa mais que de onde vem.
2. Assistir primeiro: o vídeo e a live são o centro; o resto do app serve para chegar até eles depressa.
3. Rápido e leve: abrir em segundos, nada travando, pouco peso ao lado de um jogo.
4. Familiar para quem vem do YouTube e da Twitch: nada de reaprender o básico.
5. Poder para quem usa todo dia: atalhos, abas, Multi e ajustes sem atrapalhar quem está começando.
