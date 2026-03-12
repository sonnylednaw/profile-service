package mayson.profileservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import mayson.profileservice.repository.ShoppingReceiptItemRepository;
import mayson.profileservice.repository.ShoppingReceiptRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ShoppingReceiptServiceParseItemsTest {

    @Test
    void shouldParseChedrauiStyleLineItems() throws Exception {
        ShoppingReceiptService service = newService();
        String ocrText = """
                Higiene familiar
                1.000 Acondicionador Mar 33,90 33,90 B
                Farmacia
                1,000 Suero Electrolit Pp 33.00 33.00 A
                1,000 Suero Electrolit H 22,00 22.00 A
                Lacteos y alimentos refrigerados
                1.000 QsoNocheMancReb400 112.00 112.00 A
                Congelados
                1,000 M Azul FS 907 sr 200,00 200.00 A
                Panificadora
                1,000 Taco Crema 13,00 13.00 K
                1.000 Sin Gluten Red Pla 22,00 22.00 K
                TOTAL M.N.$ 435.90
                """;

        List<ParsedLine> parsed = invokeParseItems(service, ocrText);

        assertTrue(parsed.size() >= 7, "Expected at least 7 line items for chedraui sample");
        assertTrue(parsed.stream().anyMatch(item -> item.name.toLowerCase().contains("suero electrolit") && eq(item.price, "33.00")));
        assertTrue(parsed.stream().anyMatch(item -> item.name.toLowerCase().contains("sin gluten") && eq(item.price, "22.00")));
        assertFalse(parsed.stream().anyMatch(item -> item.name.toLowerCase().contains("total")));
    }

    @Test
    void shouldIgnoreDiscountNoiseAndKeepHomeDepotItems() throws Exception {
        ShoppingReceiptService service = newService();
        String ocrText = """
                *aqueta mata insecto $ 118.00
                SLPS @1 2026 D48 $ 13.00-
                WINT eHUYENTADOR ELE $ 169.00
                SLPS 226 D48 $ 14.00-
                TIERRA PREPARADA 5 K $ 75.00
                TOTAL MN. $ 335.00
                """;

        List<ParsedLine> parsed = invokeParseItems(service, ocrText);

        assertTrue(parsed.size() >= 3, "Expected at least 3 real product lines");
        assertTrue(parsed.stream().anyMatch(item -> item.name.toLowerCase().contains("tierra preparada") && eq(item.price, "75.00")));
        assertFalse(parsed.stream().anyMatch(item -> item.name.toLowerCase().contains("slps")));
    }

    private ShoppingReceiptService newService() {
        return new ShoppingReceiptService(
                mock(ShoppingReceiptRepository.class),
                mock(ShoppingReceiptItemRepository.class),
                new ObjectMapper(),
                "",
                "",
                "",
                45
        );
    }

    @SuppressWarnings("unchecked")
    private List<ParsedLine> invokeParseItems(ShoppingReceiptService service, String text) throws Exception {
        Method method = ShoppingReceiptService.class.getDeclaredMethod("parseItems", String.class);
        method.setAccessible(true);
        List<Object> raw = (List<Object>) method.invoke(service, text);
        return raw.stream().map(this::toParsedLine).toList();
    }

    private ParsedLine toParsedLine(Object value) {
        try {
            Method nameM = value.getClass().getDeclaredMethod("name");
            Method priceM = value.getClass().getDeclaredMethod("price");
            String name = String.valueOf(nameM.invoke(value));
            BigDecimal price = (BigDecimal) priceM.invoke(value);
            return new ParsedLine(name, price);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private boolean eq(BigDecimal value, String expected) {
        return value != null && value.compareTo(new BigDecimal(expected)) == 0;
    }

    private record ParsedLine(String name, BigDecimal price) {}
}

