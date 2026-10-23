package com.clinicflow.domain.port;
import java.util.List;

public class SlotTakenException extends DomainException {
    private final List<SlotView> suggestions;
    public SlotTakenException(List<SlotView> suggestions) {
        super("That slot has just been taken");
        this.suggestions = List.copyOf(suggestions);
    }
    public List<SlotView> suggestions() { return suggestions; }
}
