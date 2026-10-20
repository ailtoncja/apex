# Ideias de UI/UX para o Apex

Avaliação feita em 18/10/2026 sobre o app rodando (tema Subaru preto e dourado, janela 1360x820, dados reais de uso): Início, Ao vivo,
Inscrições, Histórico, Ajustes, Busca, página de vídeo, página de live com chat e Multi. Método: só revisão de design; o detector
automático do kit lê HTML e não se aplica a um app Compose/Skia.

O que precisa continuar (decisão do autor): os temas Subaru e a animação do carro, o jeito YouTube (barra lateral, busca no topo, grade
de miniaturas), os atalhos de teclado e o comportamento do player. Nome e logo podem mudar.

## Nota de usabilidade (heurísticas de Nielsen, 0 a 4)

| # | Heurística | Nota | Principal problema |
|---|---|---|---|
| 1 | Estado do sistema visível | 2 | Live offline mostra player com 0:00 / 0:00 e botão de play; quadro do Multi mostra "895 assistindo" e "offline" ao mesmo tempo; abas ficam "Carregando…" |
| 2 | Linguagem do usuário | 3 | Português claro; alguns termos pedem explicação ("Apoiados", "Neste app" x "Conta do YouTube") |
| 3 | Controle e liberdade | 3 | Voltar, Esc, fechar abas funcionam; tirar do histórico e limpar histórico não têm desfazer |
| 4 | Consistência | 2 | Selecionado ora é branco (chips), ora dourado (botões); "Entrar" aparece branco e dourado na mesma tela; plataforma ora é etiqueta (TWITCH), ora bolinha |
| 5 | Prevenção de erros | 2 | Dá para pôr live offline no Multi; "Tentar de novo" num canal offline não leva a nada |
| 6 | Reconhecer em vez de lembrar | 2 | Nova aba, música de fundo, Multi e "abrir em nova aba" vivem no botão direito, botão do meio e atalhos, sem sinal na tela |
| 7 | Flexibilidade e eficiência | 4 | Atalhos para tudo, abas, Multi, menus de contexto, filtros que se combinam |
| 8 | Estética e minimalismo | 2 | Muita moldura empilhada (título do Windows + barra superior + faixa de abas + chips) antes do conteúdo; o tema quase não aparece |
| 9 | Recuperação de erros | 2 | Mensagens claras, mas sem saída útil (offline sem "ver VODs" ou "avisar quando entrar") |
| 10 | Ajuda e documentação | 2 | Só a lista de atalhos (? ou F1); nada explica Multi, abas ou música de fundo na primeira vez |
| | **Total** | **24/40** | **Aceitável**: base boa, falta acabamento |

## Veredito de identidade

Hoje o Apex parece um YouTube escuro com um pontinho dourado. Trocando o logo, qualquer app de vídeo poderia usar essa interface sem
mudar nada. O tema Subaru preto e dourado se resume a um ponto ao lado de "APEX", ao ícone da página aberta e a dois botões; os chips
selecionados são brancos, os cartões são os do YouTube, e a ideia que só o Apex tem ("as três plataformas como uma só") aparece apenas
como etiquetas TWITCH e KICK nas miniaturas. A barra de título branca do Windows em cima de um app preto quebra tudo logo de cara.

## O que já funciona

- **"Quem está ao vivo" em primeiro lugar.** Início e Ao vivo abrem com "Seus canais ao vivo", com selo AO VIVO, espectadores e jogo:
  a pergunta mais comum de quem abre o app é respondida no primeiro olhar.
- **Familiar e poderoso ao mesmo tempo.** Quem vem do YouTube não precisa aprender nada, e quem usa todo dia tem atalhos, abas, Multi,
  menus de contexto e filtros.
- **Inscrições unificadas.** Contagem por plataforma, fileira de avatares, "Novidades / Ao vivo / Apoiados / Canais" e busca dentro das
  inscrições: é a tela que mais entrega a promessa de "tudo num lugar só".

## Problemas prioritários

1. **[P1] A moldura come o conteúdo.** Barra de título do Windows (31 px, branca) + barra superior (~60 px) + faixa de abas (~55 px)
   + chips (~50 px): numa janela de 820 px, o primeiro cartão começa em ~290 px. A faixa de abas aparece em todas as telas, inclusive
   com abas "Carregando…". **Saída:** barra de título escura ou própria, abas dentro da barra superior (como no Chrome) e faixa só
   quando houver duas abas ou mais.
2. **[P1] Estados mortos e contraditórios.** Live offline: player preto com controles e 0:00 / 0:00, título igual ao login
   ("zackrawrr"), caixa "Sem descrição." e o chat rodando ao lado. Multi: quadro diz "895 assistindo" e "offline". Abas novas
   abertas por link ficam "Carregando…" até tocar. **Saída:** desenhar o estado offline (foto grande do canal, "Offline · última
   live há 3 h", botões "Ver VODs", "Avisar quando entrar ao vivo" e "Abrir chat"), tirar do Multi ou encolher quem está offline,
   e preparar o título das abas ao abrir.
3. **[P1] O tema não assume a identidade.** No Subaru preto e dourado o dourado só pinta pontos; seleção, foco e destaque usam
   branco ou cinza. **Saída:** o dourado vira a cor de tudo que está "ativo" (chip selecionado, página aberta, foco, progresso,
   botão principal), o azul do Subaru azul vira fundo de verdade, e detalhes de rali entram onde fazem sentido (selo AO VIVO, logo,
   números).
4. **[P2] Página de vídeo com ações demais.** Curtir, não curtir, Salvar, Playlist, Copiar link e Abrir no site quebram em duas
   linhas ("Abrir no site" sozinho embaixo). No vídeo do YouTube gravado, o replay do chat ocupa o melhor lugar da coluna direita
   e empurra "A seguir" para baixo. **Saída:** Curtir, Salvar e um botão "…" com o resto; coluna direita com abas "A seguir" e
   "Chat gravado", "A seguir" primeiro.
5. **[P2] Recursos escondidos.** Nova aba (botão do meio), música de fundo (botão direito), menu do player (botão direito) e
   "Adicionar ao Multi" não têm nenhum sinal visível. **Saída:** botões que aparecem ao passar o mouse no cartão (Assistir depois,
   Nova aba, Ouvir em segundo plano, + Multi) e dicas de primeira vez.

## Por pessoa

- **Primeira vez (Jordan):** abre os Ajustes e a primeira coisa é e-mail e senha da Conta do Apex, que ele não precisa; conectar
  YouTube e Twitch, que enchem o app, está embaixo. Nenhuma tela inicial explica que dá para importar quem ele segue. A faixa de abas
  com "Carregando…" aparece sem ele ter aberto nada.
- **Quem usa todo dia (Alex):** ótimo nos atalhos; falta uma paleta de comandos (ir para canal, abrir live, pôr no Multi, trocar tema
  sem sair do teclado), favoritos fixos na barra lateral e reordenar o Multi arrastando.
- **Quem joga com o Apex no segundo monitor:** quer só player e chat; a página da live gasta metade da altura com título, botões,
  descrição e "Mais ao vivo". O modo cinema existe (T), mas o chat não fica ao lado nele. Falta uma janela flutuante "sempre por cima".
- **Acessibilidade (Sam):** ícones sem texto nos quadros do Multi (som, chat, expandir, fechar) e no player; etiquetas de 11-12 sp em
  cinza; foco do teclado pouco visível.

## Observações menores

- Botão de atualizar (F5) e menu (☰) na barra superior repetem o que já tem atalho e a barra lateral.
- Fileira de categorias do Início com 11 chips + Filtros: muita escolha de uma vez.
- Resultado da Kick na busca sem miniatura (quadro cinza) e botões "Seguir" dourados dominando a lista.
- Ajustes é uma coluna longa sem índice; "Servidor" aparece apagado sem explicação.
- "Tentar de novo" num canal offline sugere erro de rede.

## Ideias

### Rápidas (horas cada)

1. **Barra de título no tema.** No Windows 11 dá para pintar a barra de título com a cor do tema (ou ao menos deixá-la escura).
   Some a faixa branca no topo.
2. **Abas só quando servem.** Faixa escondida com uma aba; títulos e capas preparados ao abrir; botão "Abas (5)" compacto na barra
   superior em vez de uma fileira inteira.
3. **Estado offline de verdade** na página da live e no Multi (ver problema 2).
4. **Botão "…" na página do vídeo** juntando Playlist, Copiar link, Abrir no site e Ouvir em segundo plano.
5. **Dourado = ativo** nos temas Subaru: chips selecionados, página aberta, foco, barra de progresso, botão principal.
6. **Ações ao passar o mouse nos cartões:** Assistir depois, Nova aba, Ouvir em segundo plano, + Multi.
7. **Ajustes reordenados:** contas das plataformas primeiro (é o que enche o app), Conta do Apex depois, índice lateral.

### Médias (dias cada)

8. **Barra superior estilo navegador:** abas dentro da própria barra (no lugar da barra de título do Windows), busca no centro, conta
   à direita. Ganha cerca de 110 px de altura em todas as telas, sem sair do jeito YouTube.
9. **Barra lateral "Ao vivo agora" unificada:** os canais que você segue e estão ao vivo, das três plataformas juntas, ordenados por
   espectadores, com bolinha da plataforma; favoritos fixos no topo; recolhida, vira uma coluna de avatares com o anel ao vivo.
10. **Modo arena para lives:** player e chat lado a lado ocupando a altura toda, informações abaixo da dobra, "Voltar ao vivo" bem
    visível, largura do chat ajustável arrastando.
11. **Primeira execução em três passos:** escolher o tema (com a animação do rali), conectar contas lendo o navegador, importar quem
    você segue; o Início já abre cheio.
12. **Paleta de comandos (Ctrl+K):** ir para canal, abrir live, pôr no Multi, trocar tema, abrir ajuste, tudo pelo teclado.
13. **Avisos de "entrou ao vivo"** dos canais favoritos, com "Abrir" e "Pôr no Multi", também como notificação do Windows.

### Grandes (semanas)

14. **Multi como estúdio:** arrastar para reordenar, layouts prontos (1 grande + 3, 2x2), som que segue o mouse, e um **chat
    unificado** juntando os chats de todas as lives com uma faixa da cor da plataforma em cada mensagem.
15. **Janela flutuante "sempre por cima"** com o player (e o chat opcional), para quem joga; separada da janela principal.
16. **Outros idiomas** (inglês e espanhol primeiro), já que o público é geral.
17. **Identidade nova.** Como nome e logo podem mudar, vale uma rodada de direções visuais com opções lado a lado (o kit faz isso),
    usando os temas Subaru como ponto de partida e não como enfeite: por exemplo, o rali como linguagem (selos como placas de
    número, tempos de live como cronômetro de especial, dourado e azul em campos inteiros), ou uma versão neutra e refinada do
    jeito YouTube. A escolha é sua, numa página com as opções desenhadas.
