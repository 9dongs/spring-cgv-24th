package com.spring_cgv_24th.domain.screening.repository;

import com.spring_cgv_24th.domain.screening.entity.ScreeningSeat;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScreeningSeatRepository extends JpaRepository<ScreeningSeat, Long> {

    List<ScreeningSeat> findAllByScreeningIdOrderByRowNoAscColumnNoAsc(Long screeningId);
}
