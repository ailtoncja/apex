# Abrir o Apex para o público

O servidor já está pronto para receber qualquer pessoa. Aqui está o que **já foi feito** e o que **só você pode fazer**
(porque envolve conta, dinheiro ou decisão sua).

## Já feito no código

- Cadastro só com aceite dos Termos de Uso e da Política de Privacidade (o servidor guarda a data e a versão aceitas).
- Páginas públicas: `/` (início), `/terms`, `/privacy`, `/account/delete` (excluir a conta pelo site).
- Direitos da LGPD: baixar todos os dados (app: Ajustes › Conta do Apex › *Baixar meus dados*; API: `GET /v1/me/export`) e apagar a conta.
- Proteção do banco grátis e contra abuso: teto de contas (`MAX_USERS`), contas novas e pedidos de senha limitados por IP,
  cota de dados por conta (10 MB ou 50 mil linhas), pedidos grandes demais recusados (64 KB; 4 MB na sincronização).
- Limpeza automática a cada 6 horas: sessões vencidas, links de e-mail usados e marcas antigas de itens apagados (180 dias).
- Cabeçalhos de segurança (HSTS, CSP, sem cache nas páginas de token) e logs sem os tokens dos links de e-mail.
- Licença **GPL-3.0** (`LICENSE`) e lista de bibliotecas (`NOTICE.md`). O app mostra uma tela clara se o VLC não estiver instalado.

## O que falta você fazer

### 1. Painel do Render (5 minutos) — obrigatório

Em *apex-server › Environment*, crie/confira:

| Variável | Valor |
|---|---|
| `CONTACT_EMAIL` | o e-mail que vai aparecer nos Termos e na Privacidade (para as pessoas pedirem ajuda ou seus dados) |
| `OPERATOR_NAME` | seu nome (ou da empresa) como responsável pelos dados |
| `PUBLIC_URL` | exatamente `https://apex-server-mg5l.onrender.com` (sem barra no final) |
| `MAX_USERS` | `2000` (aumente só se acompanhar o espaço do banco; o Neon grátis tem 0,5 GB) |

Depois abra `https://apex-server-mg5l.onrender.com/privacy` e confira se o seu contato aparece.
Sem `CONTACT_EMAIL` as páginas dizem que o contato "ainda não foi configurado" (o servidor avisa isso no log ao subir).

### 2. E-mails de verdade (recuperar senha, confirmar e-mail)

Hoje o servidor **não consegue mandar e-mail para qualquer pessoa**: sem `RESEND_API_KEY`, os e-mails só vão para o log.
Quem esquecer a senha fica sem como recuperar. Para resolver:

1. Tenha um domínio (cerca de R$ 40 por ano, por exemplo em registro.br). Pode ser só para isso.
2. Crie a conta grátis no [Resend](https://resend.com), adicione o domínio e copie os registros DNS que ele pedir (SPF/DKIM).
3. No Render defina `RESEND_API_KEY` e `MAIL_FROM` (por exemplo `Apex <nao-responda@seudominio.com>`).

Enquanto isso não for feito, avise as pessoas, ou peça para usarem uma senha que não vão esquecer (o contato dos Termos serve de plano B).

### 3. Evitar que o servidor durma (opcional)

O plano grátis do Render dorme depois de 15 minutos sem uso e leva cerca de 1 minuto para acordar. O app já espera e tenta de novo,
mas dá para manter o servidor acordado com um monitor grátis (por exemplo o UptimeRobot) chamando `https://apex-server-mg5l.onrender.com/health`
a cada 5 minutos. Se crescer, o plano pago do Render (US$ 7/mês) acaba com isso.

### 4. Neon (opcional, recomendado)

- Crie uma *branch* separada para testes manuais e deixe a principal só para as contas reais. Os testes automáticos já usam um banco embutido.
- Olhe de vez em quando o espaço usado (painel do Neon). Se chegar perto de 0,5 GB, reduza o `MAX_USERS` ou passe para um plano pago.

### 5. Distribuir o app (feito: repositório público + Release 1.0.0)

- Como o Apex é **GPL-3.0**, quem receber o programa precisa poder obter o código: por isso o repositório `ailtoncja/apex` é **público**
  (o histórico foi verificado antes: não tem senhas, chaves nem strings de conexão, e os commits usam o e-mail `noreply` do GitHub).
  Nunca commite arquivos `.env` nem a string de conexão do Neon.
- `Empacotar.cmd` gera em `dist\`: o **zip portátil**, os instaladores **`.msi` e `.exe`** (atalho no menu Iniciar e na área de trabalho, instalação só para o
  usuário atual, sem administrador) e o `SHA256SUMS.txt`. Todos trazem o Java 21; quem receber só precisa do **VLC 64 bits**.
- Ele usa duas ferramentas que ficam **fora do projeto**, em `%USERPROFILE%\.apex\`: o JDK Temurin 21 (`jdk\`) e o WiX Toolset 3.14 (`wix\`). Em outro PC, baixe
  o zip do Temurin 21 (adoptium.net) e o `wix314-binaries.zip` (github.com/wixtoolset/wix3) e extraia nessas pastas.
- **Nova versão:** mude `packageVersion` em `composeApp/build.gradle.kts` e `VERSION` em `Empacotar.cmd`, rode `Empacotar.cmd`, crie uma tag e uma Release:
  `gh release create v1.0.1 dist/Apex-1.0.1* dist/SHA256SUMS.txt --title "Apex 1.0.1" --notes "..."`.
- O servidor mostra o link da página de Releases na página inicial (mude com `DOWNLOAD_URL` no Render se hospedar em outro lugar).
- Sem assinatura de código, o Windows (SmartScreen) mostra um aviso na primeira abertura (o `LEIA-ME` e o README explicam o que clicar). A assinatura é paga
  por ano; deixe para quando houver público.

### 6. Fase 2: ligar com as plataformas por OAuth oficial

Você precisa criar os aplicativos nas plataformas (eu não consigo por você):

- **Google Cloud:** projeto, tela de consentimento e o escopo do YouTube (sensível: pede verificação com vídeo, política de privacidade e domínio;
  antes disso só 100 usuários de teste e 10 mil unidades de cota por dia).
- **Twitch:** aplicativo em dev.twitch.tv (a Twitch não permite seguir pela API, só ler quem você segue).
- **Kick:** aplicativo no portal de desenvolvedores, depois de confirmar o que a API oferece.

### 7. Revisão jurídica (quando o app crescer)

Os Termos e a Política foram escritos em linguagem simples, sem revisão de advogado. Para um público grande ou qualquer receita,
vale uma revisão (em especial transferência internacional de dados, idade mínima e a questão dos termos do YouTube).

## Riscos que continuam

- **Termos do YouTube:** o Apex usa a API interna do YouTube e o yt-dlp, que o YouTube não autoriza. Pode haver bloqueios ou notificações.
  Por isso a conta do Apex é opcional e os Termos avisam que o acesso às plataformas é por conta e risco de quem usa.
- **Mudanças nas plataformas:** YouTube, Twitch e Kick mudam as APIs sem aviso; o app precisa de atualizações frequentes.
- **Login real:** curtir, inscrever, playlists e histórico com conta real ainda não foram testados (veja o README).
