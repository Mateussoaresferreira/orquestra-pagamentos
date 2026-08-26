# ADR 0006: comparação champion/challenger de risco

## Status

Aceita.

## Contexto

Alterar uma política antifraude diretamente pode aumentar recusas legítimas ou
aprovar operações inadequadas. É necessário medir uma versão candidata usando
o mesmo contexto da decisão real, sem produzir efeitos financeiros.

## Decisão

- o champion permanece como única fonte da decisão de negócio;
- a amostra do challenger é determinística por compra e configurável de 0% a 100%;
- a avaliação candidata ocorre após o commit e em transação independente;
- falhas do challenger são isoladas, registradas em log e métrica;
- versões, sinais, pontuações e classificação da divergência ficam persistidos;
- consultas são isoladas por empresa e limitadas a janelas de até 90 dias;
- promoção de modelo é explícita e nunca automática.

## Consequências

A equipe pode comparar políticas com tráfego real e construir critérios de
promoção auditáveis. Há um pequeno custo adicional de CPU e escrita para a
amostra selecionada. A avaliação em sombra não oferece rollback de modelo nem
substitui validação estatística, revisão humana e implantação gradual.
