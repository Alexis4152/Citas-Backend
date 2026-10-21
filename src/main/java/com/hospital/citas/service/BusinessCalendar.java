package com.hospital.citas.service;

import com.hospital.citas.entity.NonBusinessDay;
import com.hospital.citas.repository.NonBusinessDayRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** Cuenta días hábiles (sin sábados, domingos ni días inhábiles oficiales) para los plazos ARCO. */
@Service
@RequiredArgsConstructor
public class BusinessCalendar {

    private final NonBusinessDayRepository nonBusinessDayRepository;
    private final AtomicReference<Set<LocalDate>> holidays = new AtomicReference<>();

    public LocalDate addBusinessDays(LocalDate from, int days) {
        LocalDate date = from;
        int added = 0;
        while (added < days) {
            date = date.plusDays(1);
            if (isBusinessDay(date)) added++;
        }
        return date;
    }

    public boolean isBusinessDay(LocalDate date) {
        DayOfWeek d = date.getDayOfWeek();
        return d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY && !holidays().contains(date);
    }

    private Set<LocalDate> holidays() {
        Set<LocalDate> cached = holidays.get();
        if (cached == null) {
            cached = nonBusinessDayRepository.findAll().stream().map(NonBusinessDay::getDay).collect(Collectors.toUnmodifiableSet());
            holidays.set(cached);
        }
        return cached;
    }
}
