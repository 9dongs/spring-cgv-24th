package com.spring_cgv_24th.domain.theater.repository;

import com.spring_cgv_24th.domain.theater.entity.Auditorium;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditoriumRepository extends JpaRepository<Auditorium, Long> {

    @EntityGraph(attributePaths = "type")
    List<Auditorium> findAllByTheaterIdOrderByIdAsc(Long theaterId);
}
