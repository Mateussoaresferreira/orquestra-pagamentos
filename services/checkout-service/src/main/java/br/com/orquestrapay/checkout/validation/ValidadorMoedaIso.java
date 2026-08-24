package br.com.orquestrapay.checkout.validation;

import java.util.Currency;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ValidadorMoedaIso implements ConstraintValidator<MoedaIso, String> {

    @Override
    public boolean isValid(String valor, ConstraintValidatorContext contexto) {
        if (valor == null || valor.isBlank()) {
            return true;
        }

        try {
            return Currency.getInstance(valor).getCurrencyCode().equals(valor);
        } catch (IllegalArgumentException excecao) {
            return false;
        }
    }
}
