package com.spring_cgv_24th.domain.theater.service;

import com.spring_cgv_24th.domain.theater.dto.response.AuditoriumResDTO;
import com.spring_cgv_24th.domain.theater.dto.response.TheaterResDTO;
import com.spring_cgv_24th.domain.theater.dto.request.TheaterReqDTO;
import com.spring_cgv_24th.domain.theater.entity.Theater;
import com.spring_cgv_24th.domain.theater.repository.AuditoriumRepository;
import com.spring_cgv_24th.domain.theater.repository.TheaterRepository;
import com.spring_cgv_24th.global.exception.CustomException;
import com.spring_cgv_24th.global.exception.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TheaterService {

    private final TheaterRepository theaterRepository;
    private final AuditoriumRepository auditoriumRepository;

    @Transactional
    public TheaterResDTO createTheater(TheaterReqDTO.CreateTheaterReqDTO request) {
        Theater theater = Theater.builder()
                .name(request.name())
                .address(request.name())
                .build();
        return TheaterResDTO.from(theaterRepository.save(theater));
    }

    public TheaterResDTO getTheater(Long theaterId) {
        Theater theater = theaterRepository.findById(theaterId)
                .orElseThrow(() -> new CustomException(ErrorCode.THEATER_NOT_FOUND));
        return TheaterResDTO.from(theater);
    }

    public List<TheaterResDTO> getTheaters() {
        return theaterRepository.findAll(Sort.by("id")).stream()
                .map(TheaterResDTO::from)
                .toList();
    }

    public List<AuditoriumResDTO> getAuditoriums(Long theaterId) {
        if (!theaterRepository.existsById(theaterId)) {
            throw new CustomException(ErrorCode.THEATER_NOT_FOUND);
        }

        return auditoriumRepository.findAllByTheaterIdOrderByIdAsc(theaterId).stream()
                .map(AuditoriumResDTO::from)
                .toList();
    }
}
