package com.sujikin.demo.dto;

import com.sujikin.demo.model.Person;
import com.sujikin.demo.validation.UniqueNameEmailList;
import jakarta.validation.Valid;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class PersonListWrapper {
    @Valid
    @UniqueNameEmailList
    private List<Person> persons;

}
