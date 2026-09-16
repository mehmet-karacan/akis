package tr.com.innova.akis.knowledge;

import java.util.*;

/** Pure state rules shared by persistence and executor; unknown effects never imply ownership. */
public final class WorkObjectLifecycle {
    public enum State { ALLOCATED, CREATING, READY, LOADING, SEALED, CONSUMED, CLEANUP_PENDING, DROPPED, REVIEW_REQUIRED }
    private WorkObjectLifecycle() { }
    public static void requireTransition(State from,State to) {
        boolean allowed = switch(from) {
            case ALLOCATED -> to==State.CREATING;
            case CREATING -> to==State.READY || to==State.REVIEW_REQUIRED;
            case READY -> to==State.LOADING || to==State.REVIEW_REQUIRED;
            case LOADING -> to==State.SEALED || to==State.REVIEW_REQUIRED;
            case SEALED -> to==State.CONSUMED || to==State.REVIEW_REQUIRED;
            case CONSUMED -> to==State.CLEANUP_PENDING;
            case CLEANUP_PENDING -> to==State.DROPPED || to==State.REVIEW_REQUIRED;
            case DROPPED,REVIEW_REQUIRED -> false;
        };
        if (!allowed) throw new IllegalStateException("Geçersiz çalışma nesnesi geçişi: "+from+" → "+to);
    }
    public static void requireDrop(State state,String expectedDb,String actualDb,String expectedOwner,String actualOwner,
            long expectedObjectId,long actualObjectId,String expectedHash,String actualHash,boolean runSucceeded) {
        if (state!=State.CLEANUP_PENDING || !runSucceeded || expectedObjectId<=0 || expectedObjectId!=actualObjectId
                || expectedDb==null || !expectedDb.equals(actualDb) || expectedOwner==null || !expectedOwner.equals(actualOwner)
                || expectedHash==null || !expectedHash.matches("[0-9a-f]{64}") || !expectedHash.equals(actualHash))
            throw new IllegalStateException("Çalışma tablosunun sahipliği/başarılı çalıştırma sonucu doğrulanamadı; silinmedi.");
    }
}
