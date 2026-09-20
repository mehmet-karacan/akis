package tr.com.innova.akis.knowledge;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class MappingSqlTest {
    private final List<MappingSql.Source> sources=List.of(
            new MappingSql.Source("SOURCE_1","SRC",Set.of("ID","NAME","AMOUNT","CREATED_AT")),
            new MappingSql.Source("SOURCE_2","LOOKUP",Set.of("ID","NAME")));
    private MappingSql.BoundSql compile(String sql,boolean predicate) {
        return MappingSql.render(MappingSql.parse(sql,sources,predicate),sources,predicate);
    }
    @Test void parsesFormattingNestedFunctionsAndBindValues() {
        var result=compile("TO_CHAR(src.created_at, 'YYYY-MM-DD') || ' / ' || NVL(UPPER(src.name), 'Unknown')",false);
        assertEquals("((TO_CHAR(\"SRC\".\"CREATED_AT\", ?) || ?) || NVL(UPPER(\"SRC\".\"NAME\"), ?))",result.sql());
        assertEquals(List.of("YYYY-MM-DD"," / ","Unknown"),result.parameters());
        assertEquals(Set.of(new MappingSql.Reference("SOURCE_1","CREATED_AT"),new MappingSql.Reference("SOURCE_1","NAME")),result.references());
    }
    @Test void doesNotInterpolateLiteralContentOrConfuseItWithColumnReferences() {
        var result=compile("REPLACE(SRC.NAME, 'O''Brien; -- DROP TABLE X', 'LOOKUP.ID')",false);
        assertEquals("REPLACE(\"SRC\".\"NAME\", ?, ?)",result.sql());
        assertEquals(List.of("O'Brien; -- DROP TABLE X","LOOKUP.ID"),result.parameters());
        assertEquals(Set.of(new MappingSql.Reference("SOURCE_1","NAME")),result.references());
    }
    @Test void resolvesAliasChangesFromStableAstReferences() {
        var ast=MappingSql.parse("SRC.ID + LOOKUP.ID",sources,false);
        var renamed=List.of(new MappingSql.Source("SOURCE_1","ORDERS",sources.getFirst().columns()),sources.get(1));
        assertEquals("(\"ORDERS\".\"ID\" + \"LOOKUP\".\"ID\")",MappingSql.render(ast,renamed,false).sql());
    }
    @Test void arithmeticPrecedenceAndUnaryOperatorsAreExplicitInRenderedSql() {
        var result=compile("-(SRC.AMOUNT + 1.25) * 2 / 4 - .5e1",false);
        assertEquals("((((-(\"SRC\".\"AMOUNT\" + ?)) * ?) / ?) - ?)",result.sql());
        assertEquals(List.of(new BigDecimal("1.25"),new BigDecimal("2"),new BigDecimal("4"),new BigDecimal(".5e1")),result.parameters());
    }
    @Test void acceptsFreePredicatesWithCorrectPrecedenceAndBindOrder() {
        var result=compile("NOT SRC.ID BETWEEN 1 AND 5 OR SRC.NAME NOT IN ('A', 'B') AND LOOKUP.ID IS NOT NULL",true);
        assertEquals("((NOT (\"SRC\".\"ID\" BETWEEN ? AND ?)) OR ((\"SRC\".\"NAME\" NOT IN (?, ?)) AND (\"LOOKUP\".\"ID\" IS NOT NULL)))",result.sql());
        assertEquals(List.of(new BigDecimal("1"),new BigDecimal("5"),"A","B"),result.parameters());
    }
    @Test void supportsSearchedAndSimpleCaseExpressions() {
        var searched=compile("CASE WHEN SRC.ID >= 10 THEN 'BIG' WHEN SRC.ID IS NULL THEN 'EMPTY' ELSE LOWER(SRC.NAME) END",false);
        assertEquals("(CASE WHEN (\"SRC\".\"ID\" >= ?) THEN ? WHEN (\"SRC\".\"ID\" IS NULL) THEN ? ELSE LOWER(\"SRC\".\"NAME\") END)",searched.sql());
        var simple=compile("CASE SRC.ID WHEN 1 THEN 'ONE' WHEN 2 THEN 'TWO' END",false);
        assertEquals("(CASE WHEN (\"SRC\".\"ID\" = ?) THEN ? WHEN (\"SRC\".\"ID\" = ?) THEN ? ELSE NULL END)",simple.sql());
    }
    @Test void acceptsComparisonsLikeAndNullWithoutInventingJavaNullBindTypes() {
        assertEquals("(\"SRC\".\"NAME\" NOT LIKE ?)",compile("SRC.NAME NOT LIKE '%x%'",true).sql());
        assertEquals("(\"SRC\".\"ID\" <> ?)",compile("SRC.ID != 2",true).sql());
        assertEquals("NULL",compile("NULL",false).sql());
        assertTrue(compile("NULL",false).parameters().isEmpty());
    }
    @Test void rejectsStatementsSubqueriesCommentsPackagesAndSequences() {
        for(String text:List.of("SELECT SRC.ID FROM SRC", "SRC.ID; DELETE FROM TARGET", "SRC.ID -- comment", "SRC.ID /* comment */",
                "(SELECT ID FROM DATA)","SYS.DBMS_RANDOM.VALUE()", "NEXTVAL(SRC.ID)", "SEQ.NEXTVAL", "SRC.ID@LINK", "SRC.ID '<EOF>'"))
            assertThrows(IllegalArgumentException.class,()->compile(text,false),text);
    }
    @Test void rejectsIncompleteOrMalformedInputAsValidationErrors() {
        for(String text:List.of("SRC.","SRC.ID +", "TO_CHAR(", "TO_CHAR(SRC.CREATED_AT,", "'open", "(SRC.ID", "CASE WHEN SRC.ID = 1 THEN", "SRC.ID IN ()", "1e+"))
            assertThrows(IllegalArgumentException.class,()->compile(text,text.contains(" IN ")),text);
    }
    @Test void rejectsWrongAliasesColumnsAritiesAndScalarPredicateMixing() {
        for(String text:List.of("OTHER.ID","SRC.NO_COLUMN","UPPER(SRC.NAME, 'x')","NVL(SRC.ID)","SRC.ID > 3", "UPPER(SRC.ID = 1)","CASE WHEN SRC.ID THEN 1 END"))
            assertThrows(IllegalArgumentException.class,()->compile(text,false),text);
        assertThrows(IllegalArgumentException.class,()->compile("SRC.ID",true));
        assertThrows(IllegalArgumentException.class,()->compile("SRC.ID = 1 = 2",true));
    }
    @Test void validatesStoredAstNotOnlyParsedText() {
        var ast=(ObjectNode)MappingSql.parse("UPPER(SRC.NAME)",sources,false);
        ast.put("function","EVIL_FUNC");
        assertThrows(IllegalArgumentException.class,()->MappingSql.render(ast,sources,false));
        ast.put("function","UPPER");ast.put("rawSql","DROP TABLE X");
        assertThrows(IllegalArgumentException.class,()->MappingSql.render(ast,sources,false));
        assertThrows(IllegalArgumentException.class,()->MappingSql.render(new JsonMapper().readTree("{\"kind\":\"LITERAL\",\"value\":true}"),sources,false));
    }
    @Test void enforcesTextDepthListNumericAndAstSizeBounds() {
        assertThrows(IllegalArgumentException.class,()->compile("(".repeat(40)+"SRC.ID"+")".repeat(40),false));
        assertThrows(IllegalArgumentException.class,()->compile("SRC.NAME || '"+"x".repeat(16384)+"'",false));
        assertThrows(IllegalArgumentException.class,()->compile("SRC.ID IN ("+String.join(",",Collections.nCopies(65,"1"))+")",true));
        assertThrows(IllegalArgumentException.class,()->compile("1e1000000000",false));
        assertThrows(IllegalArgumentException.class,()->compile("SRC.ID"+" + 1".repeat(1001),false));
    }
    @Test void resultCollectionsCannotBeMutated() {
        var result=compile("SRC.ID + 1",false);
        assertThrows(UnsupportedOperationException.class,()->result.parameters().clear());
        assertThrows(UnsupportedOperationException.class,()->result.references().clear());
    }
    @Test void numericLiteralsRemainExactAcrossJsonAndBrowserStorage() {
        String number="9007199254740993.12345678901234567890";
        var ast=MappingSql.parse("SRC.AMOUNT + "+number,sources,false);
        assertEquals("NUMBER",ast.path("right").path("kind").asText());
        assertTrue(ast.path("right").path("value").isTextual());
        var roundTripped=new JsonMapper().readTree(ast.toString());
        assertEquals(List.of(new BigDecimal(number)),MappingSql.render(roundTripped,sources,false).parameters());
        ((ObjectNode)roundTripped.path("right")).put("value","1 OR 1=1");
        assertThrows(IllegalArgumentException.class,()->MappingSql.render(roundTripped,sources,false));
    }
}
