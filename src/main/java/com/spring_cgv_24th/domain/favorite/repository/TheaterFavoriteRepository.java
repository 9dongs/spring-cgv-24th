package com.spring_cgv_24th.domain.favorite.repository;

import com.spring_cgv_24th.domain.favorite.entity.TheaterFavorite;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TheaterFavoriteRepository extends JpaRepository<TheaterFavorite, Long> {

    boolean existsByMember_IdAndTheater_Id(Long memberId, Long theaterId);

    Optional<TheaterFavorite> findByMember_IdAndTheater_Id(Long memberId, Long theaterId);
}
