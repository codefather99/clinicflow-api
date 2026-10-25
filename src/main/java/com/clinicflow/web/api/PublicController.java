package com.clinicflow.web.api;

import com.clinicflow.domain.port.SlotPort;
import com.clinicflow.web.dto.Dto;
import com.clinicflow.web.dto.Mappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class PublicController {
    private final SlotPort slots;
    private final String version;

    public PublicController(SlotPort slots, @Value("${clinicflow.version:0.1.0}") String version) {
        this.slots = slots;
        this.version = version;
    }

    /** Safe, idempotent. Also a cheap health probe for the host. */
    @GetMapping("/meta")
    public Dto.Meta meta() {
        return new Dto.Meta("Demo Community Clinic (fictional)", true, Instant.now(), "Africa/Lagos", version);
    }

    @GetMapping("/visit-types")
    public List<Dto.VisitType> visitTypes() {
        return slots.visitTypes().stream().map(Mappers::visitType).toList();
    }

    @GetMapping("/slots")
    public List<Dto.Slot> slots(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Mappers.slots(slots.slotsFor(date));
    }
}
