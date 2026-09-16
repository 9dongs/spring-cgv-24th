package com.spring_cgv_24th.domain.auditorium.service;

import com.spring_cgv_24th.domain.auditorium.entity.AuditoriumType;
import com.spring_cgv_24th.domain.auditorium.enums.AuditoriumKind;
import com.spring_cgv_24th.domain.auditorium.repository.AuditoriumTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class AuditoriumTypeInitializer implements ApplicationRunner {

    private final AuditoriumTypeRepository auditoriumTypeRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (AuditoriumKind kind : AuditoriumKind.values()) {
            if (auditoriumTypeRepository.findByKind(kind).isEmpty()) {
                auditoriumTypeRepository.save(AuditoriumType.builder()
                        .kind(kind)
                        .rowCount((short) 8)
                        .columnCount((short) 8)
                        .build());
            }
        }
    }
}
