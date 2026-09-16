package com.spring_cgv_24th.domain.reservation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.spring_cgv_24th.domain.reservation.dto.ReservationReqDTO;
import com.spring_cgv_24th.domain.reservation.entity.Reservation;
import com.spring_cgv_24th.domain.reservation.enums.ReservationStatus;
import com.spring_cgv_24th.domain.reservation.repository.ReservationRepository;
import com.spring_cgv_24th.domain.screening.entity.Screening;
import com.spring_cgv_24th.domain.screening.entity.ScreeningSeat;
import com.spring_cgv_24th.domain.screening.repository.ScreeningRepository;
import com.spring_cgv_24th.domain.screening.repository.ScreeningSeatRepository;
import com.spring_cgv_24th.global.exception.CustomException;
import com.spring_cgv_24th.global.exception.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock private ScreeningRepository screeningRepository;
    @Mock private ScreeningSeatRepository screeningSeatRepository;
    @Mock private ReservationRepository reservationRepository;
    @InjectMocks private ReservationService reservationService;

    @Test
    void occupiedSeatCannotBeReservedAgain() {
        Screening screening = mock(Screening.class);
        when(screening.getId()).thenReturn(1L);
        ScreeningSeat seat = seat(screening);
        Reservation existing = Reservation.builder().screening(screening).build();
        seat.occupy(existing);
        when(screeningRepository.findById(1L)).thenReturn(Optional.of(screening));
        when(screeningSeatRepository.findByIdAndScreeningIdForUpdate(2L, 1L))
                .thenReturn(Optional.of(seat));

        CustomException error = assertThrows(CustomException.class,
                () -> reservationService.createReservation(
                        new ReservationReqDTO.CreateReservationDTO(1L, List.of(2L))));

        assertEquals(ErrorCode.SEAT_ALREADY_RESERVED, error.getErrorCode());
        assertSame(existing, seat.getReservation());
        verifyNoInteractions(reservationRepository);
    }

    @Test
    void cancellationReleasesSeatsAndChangesStatus() {
        Screening screening = mock(Screening.class);
        Reservation reservation = Reservation.builder().screening(screening).build();
        ScreeningSeat first = seat(screening);
        ScreeningSeat second = seat(screening);
        first.occupy(reservation);
        second.occupy(reservation);
        when(reservationRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(reservation));
        when(screeningSeatRepository.findAllByReservationIdForUpdate(1L))
                .thenReturn(List.of(first, second));

        reservationService.cancelReservation(1L);

        assertNull(first.getReservation());
        assertNull(second.getReservation());
        assertEquals(ReservationStatus.CANCELLED, reservation.getStatus());
        assertNotNull(reservation.getCancelledAt());
    }

    private static ScreeningSeat seat(Screening screening) {
        return ScreeningSeat.builder()
                .screening(screening)
                .rowNo((short) 1)
                .columnNo((short) 1)
                .price(14_000)
                .build();
    }
}
