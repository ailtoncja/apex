# Apex — contexto para geração 3D

Este arquivo descreve o Apex para quem vai gerar imagens ou objetos 3D dele (ícone, logo, cena de divulgação). Tudo aqui foi tirado do
código do app: cores, logo, ícone e temas são os mesmos que aparecem na tela.

## O que é o Apex

- App de **desktop para Windows** que reúne **YouTube, Twitch e Kick** num lugar só: vídeos, lives, VODs, clipes e o chat das lives.
- Jeito do app: **escuro, limpo, direto**. Sem anúncios, sem Shorts, sem distração. Player grande, barra lateral fina, cantos arredondados.
- Público: quem assiste lives e vídeos o dia inteiro e quer um player rápido (os vídeos abrem em ~1 s) e sem enrolação.
- Tom da marca: **preciso, rápido, sóbrio**. Nada de mascote fofo, nada de neon exagerado. Pense em "painel de carro de rali à noite".
- Nome: sempre **APEX**, em caixa alta. "Apex" é o ponto mais interno de uma curva — a trajetória perfeita. É daí que vem o nome, e o tema
  Subaru (azul de rali com rodas douradas) é uma piscadela para isso.

## O logo (como está no app)

Um **ponto vermelho** seguido da palavra **APEX**:

- Ponto: círculo perfeito, vermelho `#FF3B30`, com 12 px de diâmetro quando o texto tem 19 px.
- Palavra: "APEX" em sans-serif **extra-bold**, caixa alta, com **espaçamento largo entre as letras** (letter-spacing ≈ 3 px em 19 px de
  fonte), cor quase branca `#F2F2F7` (no tema claro, `#15151C`). A fonte é a sans-serif padrão do sistema (estilo Roboto/Inter); nada de serifa.
- O ponto fica à esquerda, alinhado ao centro vertical do texto, com 8 px de folga até o "A".
- Em 3D: o ponto pode virar uma **esfera** (vermelha, fosca ou com brilho suave) e as letras um **bloco extrudado** em branco quente.

## O ícone do app (como está no app)

- Quadrado de cantos arredondados (raio = 22% do lado) na cor `#0A0A0F` (quase preto, levemente azulado).
- No centro, um **círculo vermelho `#FF3B30`** com raio = 27% do lado (ou seja, ocupa pouco mais da metade da largura).
- Só isso. Sem texto, sem borda, sem sombra. É o ponto do logo sobre o fundo do app.

## Cores

Tema escuro (o padrão):

| Papel | Cor |
|---|---|
| Fundo | `#0A0A0F` |
| Superfície (cartões) | `#13131A` |
| Superfície alta | `#1B1B25` / `#262633` |
| Contorno | `#2C2C3B` |
| **Destaque (o vermelho da marca)** | `#FF3B30` (pressionado `#D92E25`) |
| Texto | `#F2F2F7` |
| Texto apagado | `#9494AA` / `#626277` |
| "AO VIVO" | `#FF2D55` |
| Apoio (dourado) | `#FFB300` |

Outros temas do app (para variações):

- **Claro**: fundo `#FFFFFF`, superfície `#F7F7FA`, destaque `#E5342B`, texto `#15151C`.
- **Subaru azul e dourado**: fundo `#060D26`, superfície `#0A1633`, contorno `#263F80`, destaque **dourado `#FFC629`**, texto `#EAF0FF`, ao vivo `#FF4560`.
- **Subaru preto e dourado**: fundo `#080808`, superfície `#121212`, destaque **dourado `#EDB21B`**, texto `#F4EFE3`.

Cores das plataformas (só quando a cena mostrar as três): YouTube `#FF3B30`, Twitch `#9146FF`, Kick `#53FC18`.

## O que fazer e o que evitar

Fazer: fundo escuro quase preto; **um** ponto de cor forte (o vermelho); materiais foscos ou acetinados; luz lateral suave com um reflexo
fino nas bordas; cantos arredondados; composição com muito espaço vazio; letras largas e espaçadas.

Evitar: gradientes de arco-íris; neon roxo/ciano genérico de "gamer"; mascotes, personagens, rostos; efeitos de vidro exagerados; texto além de
"APEX"; logos do YouTube/Twitch/Kick (use só as cores); serifas; qualquer coisa que pareça o logo de outra marca com ponto vermelho.

## Prompts prontos (em inglês, que é o que os geradores entendem melhor)

### 1. Ícone 3D do app

```
3D app icon of "Apex": a rounded square (corner radius 22% of the side) in near-black #0A0A0F, with a single perfect red sphere #FF3B30
centered on it, sphere diameter about 55% of the square width. Matte soft-touch plastic for the square, satin red for the sphere with a thin
rim highlight. Studio product render, three-quarter top view, soft key light from the upper left, subtle contact shadow, dark gray seamless
background #111117. No text, no logos, no extra shapes. Clean, minimal, premium. 8k, octane render.
```

### 2. Logo 3D (ponto + APEX)

```
3D wordmark "APEX" in bold geometric sans-serif, all caps, wide letter spacing, extruded 20% of the cap height, off-white #F2F2F7 matte
finish. To the left of the "A", a red sphere #FF3B30 the size of a lowercase x-height, vertically centered. Floating over a near-black
#0A0A0F studio backdrop, soft overhead light, gentle reflections on a dark glossy floor, slight camera tilt. Minimal, premium, no extra
elements, no other text.
```

### 3. Cena de divulgação (tela do app em 3D)

```
Cinematic 3D render of a sleek desktop app window floating in a dark studio: near-black UI #0A0A0F with rounded corners, a large video
player on the left, a slim sidebar, a chat column on the right with faint rows of text, one small red "live" dot #FF2D55 and a red accent
#FF3B30. In the top-left corner of the window a tiny logo: red dot and the word "APEX" in wide-spaced bold letters. Thin light rim on the
window edges, soft volumetric light, dark floor reflection, lots of empty space around. No readable text other than "APEX", no people,
no other brand logos. Premium tech product shot.
```

### 4. Variação Subaru (azul de rali e dourado)

```
Same Apex app icon as before, rally edition: rounded square in deep navy #060D26 with a gold sphere #FFC629 in the center, brushed
gold metal with soft reflections, navy matte plastic body, a faint gold rim light. Studio render on a dark backdrop, no text.
```

### Negativo (para geradores que aceitam "negative prompt")

```
text, letters other than APEX, watermark, rainbow gradient, purple cyan neon, mascot, character, face, glass bubbles, clutter, extra icons,
other brand logos, serif font, low quality, blurry
```

## Cola rápida (uma frase, para colar em qualquer lugar)

> Apex é um app de desktop escuro e minimalista para assistir YouTube, Twitch e Kick. Marca: ponto vermelho `#FF3B30` + "APEX" em
> sans-serif extra-bold, caixa alta e letras espaçadas, sobre preto `#0A0A0F`. Ícone: quadrado arredondado preto com um círculo vermelho no
> centro. Tom: preciso, rápido, sóbrio, inspiração em rali (tema alternativo azul `#060D26` com dourado `#FFC629`). Sem mascote, sem neon.
