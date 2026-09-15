package com.spring_cgv_24th.domain.store.repository;

import com.spring_cgv_24th.domain.store.entity.TheaterStock;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TheaterStockRepository extends JpaRepository<TheaterStock, Long> {

    @EntityGraph(attributePaths = "product")
    List<TheaterStock> findAllByTheater_IdOrderByProduct_IdAsc(Long theaterId);
}
