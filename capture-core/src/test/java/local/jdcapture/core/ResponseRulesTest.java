package local.jdcapture.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class ResponseRulesTest {
    private String entry(String rows, boolean verification) {
        return "<script>window.__react_data__ = {\"activityData\":{\"activityId\":\"02015183\",\"pageId\":\"5923154\",\"shieldResult\":{\"intercept\":" + verification + "},\"rows\":" + rows + "}};</script>";
    }
    private String row(String id, String value) {
        return "{\"skuId\":\"" + id + "\",\"exchangeScore\":9,\"skuStatus\":" + value + "}";
    }
    @Test public void ignoresUrlSkuAndRequiresResponseIdentity() throws Exception {
        assertEquals("entry", ResponseRules.stage("https://pro.m.jd.com" + ResponseRules.ENTRY_PATH + "?skuId=unrelated", ""));
        String text = entry("[" + row(ResponseRules.SKU, "8") + "]", false);
        assertEquals(8, ResponseRules.value("entry", text));
        assertThrows(IllegalArgumentException.class, () -> ResponseRules.value("entry", text.replace("02015183", "different")));
    }
    @Test public void patchChangesOnlyTheUniqueTarget() throws Exception {
        String unrelated = row("different", "8");
        String text = entry("[" + unrelated + "," + row(ResponseRules.SKU, "8") + "]", false);
        ResponseRules.Preview p = ResponseRules.preview("entry", text, 8, 0);
        assertEquals(text.replace(row(ResponseRules.SKU, "8"), row(ResponseRules.SKU, "0")), p.text());
        assertTrue(p.text().contains(unrelated));
        assertEquals("skuStatus", p.field());
    }
    @Test public void rejectsVerificationAmbiguityAndUnexpectedValues() {
        String target = row(ResponseRules.SKU, "8");
        assertThrows(IllegalArgumentException.class, () -> ResponseRules.preview("entry", entry("[" + target + "]", true), 8, 0));
        assertThrows(IllegalArgumentException.class, () -> ResponseRules.preview("entry", entry("[" + target + "," + target + "]", false), 8, 0));
        assertThrows(IllegalArgumentException.class, () -> ResponseRules.preview("entry", entry("[" + target + "]", false), 5, 0));
        assertThrows(IllegalArgumentException.class, () -> ResponseRules.value("entry", entry("[" + row(ResponseRules.SKU, "\"8\"") + "]", false)));
    }
    @Test public void detailRequiresSuccessfulMatchingIdentity() throws Exception {
        String detail = "{\"success\":true,\"errorCode\":1000,\"data\":{\"activityId\":\"1421003\",\"wareBusinessId\":\"" + ResponseRules.SKU + "\",\"exchangeScore\":9,\"exchangeQty\":0}}";
        assertEquals(0, ResponseRules.value("checkout", detail));
        assertThrows(IllegalArgumentException.class, () -> ResponseRules.value("checkout", detail.replace("true", "false")));
        assertThrows(IllegalArgumentException.class, () -> ResponseRules.value("checkout", detail.replace("1421003", "different")));
    }
    @Test public void functionMatchingDecodesFormFieldsExactly() {
        assertTrue(ResponseRules.hasFunction("https://api.m.jd.com/api", "functionId=SEP%5FEXCHANGE%5FSUBMIT", "SEP_EXCHANGE_SUBMIT"));
        assertFalse(ResponseRules.hasFunction("https://api.m.jd.com/api", "description=functionId%3DSEP_EXCHANGE_SUBMIT", "SEP_EXCHANGE_SUBMIT"));
    }
}
