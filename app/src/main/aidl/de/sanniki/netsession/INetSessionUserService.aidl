package de.sanniki.netsession;

interface INetSessionUserService {
    String runCommand(String command) = 1;
    void destroy() = 16777114;
}
