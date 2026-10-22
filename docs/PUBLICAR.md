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
- Licença **GPL-3.0** (`LICENSE`) e lista de bibliotecas (`NOTICE.md`). O VLC vai dentro do app; se ele não carregar, o app mostra uma tela clara.

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

Sem configurar nada, o servidor **não consegue mandar e-mail para ninguém** (só escreve no log), e quem esquecer a senha fica sem como recuperar. Dois caminhos:

- **Grátis e sem domínio, agora:** um relay no Google Apps Script (ou na Vercel) que envia pelo seu Gmail, com limite de 100 e-mails por dia (Apps Script).
  Passo a passo em [docs/email-relay/README.md](email-relay/README.md). No Render: `MAIL_WEBHOOK_URL` e `MAIL_WEBHOOK_SECRET`.
- **Definitivo, com domínio:** compre um domínio (cerca de R$ 40 por ano), crie a conta grátis no [Resend](https://resend.com) (3.000 e-mails por mês, 100 por dia),
  adicione o domínio, cadastre os registros DNS que ele mostrar e, no Render, defina `RESEND_API_KEY` e `MAIL_FROM` (por exemplo `Apex <nao-responda@seudominio.com>`).
  O Resend tem prioridade sobre o relay. (O Render grátis bloqueia SMTP, por isso não dá para usar o Gmail direto.)

Para conferir, abra `https://apex-server-mg5l.onrender.com/v1/info`: deve mostrar `"mailEnabled":true`.

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
  usuário atual, sem administrador) e o `SHA256SUMS.txt`. Todos trazem o Java 21 e o VLC 3.0.24; quem receber não precisa instalar nada.
- Ele usa duas ferramentas que ficam **fora do projeto**, em `%USERPROFILE%\.apex\`: o JDK Temurin 21 (`jdk\`) e o WiX Toolset 3.14 (`wix\`). Em outro PC, baixe
  o zip do Temurin 21 (adoptium.net) e o `wix314-binaries.zip` (github.com/wixtoolset/wix3) e extraia nessas pastas.
- **Nova versão:** mude `appVersion` em `composeApp/build.gradle.kts` (é a única fonte: vai para o instalador e para o app) e rode `Empacotar.cmd`.
  Ele gera os instaladores, o zip, o `SHA256SUMS.txt` **e a assinatura** `SHA256SUMS.txt.sig`. Depois crie a Release com todos eles:
  `gh release create v1.0.2 dist/Apex-1.0.2* dist/SHA256SUMS.txt dist/SHA256SUMS.txt.sig --title "Apex 1.0.2" --notes "..."`.
  Quem já tem o Apex 1.0.1 ou mais novo recebe a atualização sozinho (confere ao abrir e a cada 6 horas). Quem tem a 1.0.0 atualiza uma vez à mão.
- **A chave que assina as atualizações** fica em `%USERPROFILE%\.apex\signing\apex-update.key.enc`, **protegida por senha**
  (PBKDF2-HMAC-SHA256 com 600 mil iterações + AES-256-GCM; fora do repositório, o `.gitignore` bloqueia `*.key` e `*.key.enc`).
  Sem ela não dá para publicar atualizações que os apps aceitem (só mandando um instalador novo à mão), e quem a roubar consegue assinar uma atualização falsa.
  - **Migração (uma vez só), se você ainda tem a `apex-update.key` antiga sem senha:** num `cmd` ou PowerShell, na pasta do projeto,
    `"%USERPROFILE%\.apex\jdk\jdk-21.0.12.1+1\bin\java.exe" tools\ProtegerChave.java` (ou qualquer `java` 21+). Ele pede a senha duas vezes
    (mínimo 12 caracteres; prefira uma frase longa), grava `apex-update.key.enc`, confere que ele abre e devolve a mesma chave e oferece apagar o arquivo
    sem senha (digite `APAGAR`). Se preferir apagar depois de testar um `Empacotar.cmd`: `del "%USERPROFILE%\.apex\signing\apex-update.key"`.
    Apague também cópias antigas da chave sem senha (pen drive, nuvem, backups).
  - **Cópia de segurança:** guarde o `apex-update.key.enc` fora do PC (pen drive guardado, outro disco) e a **senha separada** dele (gerenciador de senhas).
    Sem a senha o `.enc` não serve para nada, nem para você.
  - **Ao empacotar:** o `Empacotar.cmd` compila, para o daemon do Gradle (`gradlew --stop`, para nenhum código do build continuar rodando) e só então o
    `tools\Sign.java` pede a senha e abre a chave. Para rodar sem ninguém digitando dá para definir `APEX_SIGN_PASSPHRASE`, mas é **menos seguro**
    (variáveis de ambiente ficam visíveis para outros programas do seu usuário e podem acabar em logs); se usar, defina só naquela janela e feche depois.
    Se só existir a chave antiga sem senha, o `Sign.java` ainda assina, com um aviso grande.
  - **Trocar a chave** (se a senha vazar, o PC for comprometido ou de tempos em tempos): gere outra com
    `java tools\GenKey.java "%USERPROFILE%\.apex\signing\apex-update-nova.key.enc"` (já nasce com senha), cole a pública em `UpdateKeys.kt` e publique uma
    versão nova assinada com a **antiga** (que os apps ainda aceitam) contendo a chave nova. Depois disso, renomeie a nova para `apex-update.key.enc`
    (guarde a antiga offline até todo mundo ter atualizado) e refaça a cópia de segurança.
- **Lista dos servidores sem anúncio da Twitch** (`config/twitch-ad-free.json`): o app baixa este arquivo do GitHub ao abrir e a cada 6 horas e
  só aceita com a assinatura (`.sig`) da mesma chave das atualizações. Para trocar servidores **sem lançar versão nova**:
  1. edite a lista `servers` (cada item é `host/caminho` com `$channel`), **aumente o `version`** (uma lista com versão menor é ignorada) e mude o `updated`;
  2. assine: `"%USERPROFILE%\.apex\jdk\jdk-21.0.12.1+1\bin\java.exe" tools\Sign.java "%USERPROFILE%\.apex\signing\apex-update.key.enc" config\twitch-ad-free.json`;
  3. faça o commit dos dois arquivos (`.json` e `.json.sig`) e o push. Os apps pegam a lista nova em até 6 horas.
  O teste `a_lista_publicada_no_repositorio_esta_assinada_com_a_chave_do_app` falha se o arquivo for mudado sem assinar de novo.
  Quem escreveu a própria lista nos Ajustes continua com a dela.
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
