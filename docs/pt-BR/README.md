# Kerosene Admin

Este repositório possui as ferramentas usadas por operadores. Hoje ele entrega
o executável `kerosene-jctl`, preservando o nome já usado nos ambientes.

Use `./gradlew test` para validar e `./gradlew installDist` para gerar a
distribuição. A CLI conversa somente com APIs autenticadas e auditadas; ela não
pode acessar bancos diretamente, guardar tokens permanentes nem assumir a
autoridade dos serviços.

Pendente: criar o remoto no GitHub, proteger a branch principal e substituir os
comandos ilustrativos de secrets pela integração escolhida para produção.
