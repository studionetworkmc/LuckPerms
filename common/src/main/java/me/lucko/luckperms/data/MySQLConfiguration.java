package me.lucko.luckperms.data;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class MySQLConfiguration {

    private final String address;
    private final String database;
    private final String username;
    private final String password;

}