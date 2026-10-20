# Segurança

## Como avisar de uma falha

Se você achou uma falha de segurança no Apex (no app ou no servidor), **não abra uma issue pública**. Use o aviso privado do GitHub:
na página do repositório, **Security › Report a vulnerability**. Descreva o que você viu, como reproduzir e o impacto.
Respondemos o quanto antes e publicamos a correção antes de divulgar os detalhes.

Falhas que valem aviso: acesso a dados de outras contas, burlar login ou limites de uso, executar código no computador de quem usa o app,
vazar senhas, tokens ou cookies. Não vale testar contra o servidor público com volume (nada de ataque de negação de serviço).

## Versões

Só a versão mais recente da [página de Releases](https://github.com/ailtoncja/apex/releases) recebe correções.

## O que o Apex faz para se proteger

**Servidor** (`server/`)
- Senhas com Argon2id; tokens de renovação guardados só como hash, girados a cada uso, com detecção de roubo (reuso encerra todas as sessões).
- Tokens de acesso JWT de 15 minutos, com assinatura, emissor, público e validade conferidos (testado: chave errada, `alg: none`, vencido, adulterado).
  Cada token leva a sessão que o gerou (`sid`) e só vale enquanto ela estiver aberta: sair, trocar a senha ou apagar a conta derruba na hora.
- Limites de uso por IP real (o cabeçalho `X-Forwarded-For` só vale na parte que os nossos proxies acrescentam), barreira de tentativas de senha por
  pessoa e por e-mail (login, apagar a conta), teto de contas, cota de dados por conta e limite de tamanho de pedido.
- Respostas que não revelam quem tem conta (login, "esqueci a senha"), sem diferença de tempo; links de e-mail de uso único e sem aparecer nos logs
  (os logs também não guardam e-mails inteiros, só `a***@dominio`).
- Cabeçalhos de segurança (HSTS, CSP, sem cache nas páginas com token), consultas sempre parametrizadas. Conexão com o banco por TLS com o
  certificado conferido (`sslmode=verify-full`, certificados confiáveis do Java) e `channelBinding=require` quando a string de conexão pede.
- Fora de container, o servidor só escuta em `127.0.0.1` (no container, `BIND_HOST=0.0.0.0`); a imagem de produção não leva o banco embutido
  de desenvolvimento e usa imagens base presas por digest.

**Risco aceito: cadastro revela se o e-mail já tem conta.** O cadastro entra na conta na hora (o app recebe a sessão na resposta e a confirmação
do e-mail não é exigida para usar), então não dá para responder igual a quem já tem conta sem redesenhar o fluxo (cadastro só ativo depois de
confirmar o e-mail). Hoje o `POST /v1/auth/register` responde `409 email_taken` para e-mail já cadastrado. Para conter quem tenta descobrir
contas em massa, o cadastro tem limite próprio por IP (`REGISTER_LIMIT_PER_HOUR`, padrão 20 por hora) além do limite geral de autenticação
(30 por minuto por IP).
- Dependências verificadas contra o banco de vulnerabilidades OSV (Jackson atualizado para 2.22.3; o relay da Vercel usa o nodemailer 10).

**App** (`composeApp/`)
- Senhas das plataformas nunca passam pelo Apex: o login é feito no navegador; o que fica guardado é cifrado com a conta do Windows (DPAPI) e,
  se a cifragem falhar, não é gravado em texto puro. Os cookies do YouTube só existem em arquivo temporário enquanto o yt-dlp roda.
- A senha e os tokens da conta do Apex só viajam por `https://` (só o próprio computador pode usar `http://`).
- Links só abrem se forem `http(s)`; endereços de vídeo vindos de dados sincronizados nunca viram opção do yt-dlp.
- O yt-dlp e o Deno baixados só são instalados se o hash SHA-256 bater com o publicado junto na release oficial.
- O perfil temporário do navegador usado no login é apagado assim que o login é lido.
- **Atualização automática:** o app só instala uma versão nova se a lista de hashes (`SHA256SUMS.txt`) estiver assinada com a chave privada do projeto
  (Ed25519; a chave pública vai dentro do app) e o instalador tiver exatamente o hash dessa lista. Os arquivos só são baixados de
  `github.com/ailtoncja/apex/releases`. Quem invadir a conta do GitHub, sem a chave de assinatura, não consegue empurrar uma atualização falsa.

## O que o Apex **não** faz

- Os instaladores ainda não têm assinatura digital: o Windows (SmartScreen) mostra um aviso na primeira abertura. Confira o hash do `SHA256SUMS.txt`.
- O app usa a API interna do YouTube e o yt-dlp, que o YouTube não autoriza (risco de bloqueio, não de segurança do seu computador).
